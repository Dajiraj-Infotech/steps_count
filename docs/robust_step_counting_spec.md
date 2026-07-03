# steps_count Android Engineering Spec v3 (Unified Final)

Sensor-only, minSdk 24, FGS + BroadcastReceiver, framework + kotlinx-coroutines only, Dart API source-compatible (additive only), v2 -> v3 migration included.

---

## 0. Scoring of the three designs

| | Correctness vs matrix | Simplicity | Migration risk | Battery |
|---|---|---|---|---|
| **A (crash-safety)** | Best. Only design with a frozen-anchor quarantine (garbage never destroys the baseline), derived-credit rule (`credit = counter - anchor`, segments cannot change totals), deterministic UUIDs, and a full kill-point enumeration. | Medium (quarantine state, segment machinery) | Low: keeps `timestamp` column name, no-op `onDowngrade`, bounded legacy-pending salvage | Good (5 min FIFO latency) |
| **B (time-model)** | Very strong on time: best rate-gate envelope (burst + sustained), strict monotone watermark that makes the legacy `timestamp > lastSync` cursor structurally safe, finest per-event segments, never-closed DB singleton. Weaknesses: CE storage + marker file instead of DPS (leaves direct boot degraded, EC-29 only partially fixed); implausible delta re-baselines immediately (a transient glitch can eat a real gap that A's quarantine self-heals). | Medium | Medium (renames columns to `start_ts`/`end_ts`, breaking A's downgrade story) | Best (10 min latency) but at a larger reboot-loss bound |
| **C (field-reliability)** | Strong lifecycle: only design with a persisted JobScheduler watchdog + user-tap resume notification (the honest Android-12+ background-FGS answer), `ensureRunning()` ladder, integrity-checked export. Weaknesses: coarse single pending interval (worst attribution), flat 5 Hz gate (432k/day, too loose over multi-day gaps), extra DB_DOWN/CLOCK_HELD states that A's anchor design makes unnecessary. | Lowest state-machine hygiene but simplest counting core | Low | OK (2 min latency, most wakeups) |

**Structure adopted: Design A** (anchor-in-transaction + derived credit + quarantine is the strongest correctness core), with B's time model and watermark invariant, and C's lifecycle/resurrection ladder grafted in.

### Explicit conflict resolutions

| Conflict | Winner | Why |
|---|---|---|
| Storage location | **A/C: device-protected storage (DPS)**, `moveDatabaseFrom`/`moveSharedPreferencesFrom` | Structurally excluded from Auto Backup (EC-2) AND readable pre-unlock (EC-29). B's CE-plus-marker leaves direct boot in a degraded RAM mode. B's `noBackupFilesDir` install marker is kept as an extra restore-detection layer (cloners that copy DPS wholesale). |
| Implausible delta | **A: quarantine (anchor frozen), not immediate re-baseline (B/C)** | Re-baselining to a plausible-looking-but-wrong value permanently forfeits the real gap. A's quarantine lets a transient glitch pass harmlessly (next sane event computes a normal delta against the untouched anchor) and self-heals real late deliveries when the same excess becomes plausible over a larger dt. |
| Rate-gate formula | **B: piecewise burst+sustained envelope** (with A's slack) | C's flat 5 Hz allows 432k/day over long gaps; A's 80k/day is slightly tight for ultra users; B's concave envelope is tight on short intervals and honest on multi-day catch-ups. |
| Backward clock | **B: always clamp to watermark+1, mark estimated** (over A's honor-large-corrections) | The hard constraint is legacy `timestamp > lastSync` compatibility; B's strict monotone watermark makes that cursor provably starvation-free. Large corrections are diag-logged; the `seq` cursor (A/C) is offered as the clean escape hatch. C's CLOCK_HELD state is dropped (complexity without correctness gain). |
| DB lifecycle | **B: process-wide singleton, never closed** | Deletes the entire EC-40 close/reopen/straggler class instead of guarding it with a closed-flag (A). |
| Mid-session hub reset (negative delta) | **A's pre-flush + C's credit-v** | Flush everything already delivered, then credit `v` (steps since hub restart) through the gate as a `gap` interval, then re-anchor. A alone forfeited post-reset steps; C alone skipped the pre-flush. |
| DB write failure | **A: frozen anchor, retry; no DB_DOWN consumption stop (C)** | With credit derived from `counter - anchor`, the in-memory state may advance freely; nothing is consumed until the transaction commits. C's "stop consuming" state is unnecessary. Reopen backoff (1/5/30 min) kept from C. |
| Resurrection ladder | **C: alarm retry + persisted JobScheduler watchdog + resume notification** | Only design that answers Android 12+ background-FGS-start denial honestly (user tap is an allowed start path). A's bare alarm and B's single verification alarm are subsets. |
| UUIDs | **A: deterministic name-based UUIDs** | Makes any hypothetical re-derivation idempotent locally and server-side (EC-10 residual for cloners). |
| Legacy `pending_steps` prefs | **A: salvage <= 5,000 as one `gap` row, discard larger to diag** (over C's discard-all) | Bounded user-friendliness: forfeiting a real hour of walking is avoidable; 5,000 caps phantom risk. |
| v2 row migration | **B's interval reconstruction + C's LEGACY_SUSPECT flag** | B's bounded back-fill (<= 60 s, non-overlapping, no midnight cross) beats A's zero-width rows for interval math; C's >= 20,000 suspect flag preserves forensics on already-poisoned history. |
| Column naming | **A: keep `timestamp` (= interval end), add `start_timestamp`** | Enables a no-op `onDowngrade` and keeps old readers working (EC-60). |
| Live buffer in queries | **B: per-segment window filtering** (over A's whole-buffer smear over `[anchorWall, now]`) | Segments carry real timestamps; a 5-minute query gets exactly its 5 minutes. |
| maxReportLatency | **A: 5 min** | Middle ground: B's 10 min doubles the reboot FIFO-loss tail; C's 2 min wakes the SoC 5x as often. |
| Silence watchdog | **B/C: 6 h** (over A's 12 h) | Faster recovery from torn-down sensor connections at negligible cost. |
| Notification refresh | **A: SCREEN_ON runtime receiver + on-flush** (midnight alarm dropped) | A non-waking midnight alarm fires only when the device wakes anyway; SCREEN_ON covers the "morning check" moment exactly and instantly. |
| Health API name | `getTrackingStatus()` (A/B) | Coin flip; A/B majority. |

---

## 1. The one big idea (all three designs converged; stated once)

**Within a boot session, the hardware cumulative counter IS the write-ahead log. SQLite holds a single durable cursor into that log (the anchor), advanced in the same transaction as every step insert.**

- `sum(step rows) == f(anchor_counter)` at all times: rows and anchor commit atomically; steps are credited exactly once by construction.
- SharedPreferences is removed from the counting path entirely (kills EC-5/6/12/13 structurally).
- The in-memory buffer is derived, non-authoritative: `unflushed = lastEventCounter - anchor_counter`. Losing it costs nothing; the hardware re-delivers.
- A DB write failure costs nothing within the boot session: the anchor does not advance, so the steps stay "in the log" and are booked when the DB recovers.
- The only loss window is when the log itself dies (reboot / hub reset), bounded in Section 5.

---

## 2. Persistent state model

### 2.1 What lives where

| Datum | Store | Why |
|---|---|---|
| Step rows, `tracker_state` (anchor), `health_log` | SQLite `step_count.db` v3, **device-protected storage** | One transaction domain (exactly-once); available pre-unlock (EC-29); never Auto-Backed-up (EC-2/EC-10). Moved via `Context.moveDatabaseFrom()` (API 24+), guarded by a `moved_to_dps` marker; on move failure, fall back to CE and retry next start with a diag row. |
| `tracking_enabled`, `moved_to_dps`, `last_start_failure` | **DPS SharedPreferences** (`steps_count_flags`) | Non-counting config only; readable pre-unlock by the boot receiver; loss is harmless. |
| Install marker (random UUID) | `noBackupFilesDir/steps_count.install` | Layer-3 restore detection (B): never backed up; cloners that copy DPS wholesale still create a fresh one. Mirrored in `tracker_state.install_id`. |
| Legacy CE prefs (`steps_count_prefs`) | Read once for migration (2.4 step 8), then cleared. |

### 2.2 Backup exclusion (defense in depth, EC-43 fix included)

The plugin's own `android/src/main/AndroidManifest.xml` (currently empty) ships merged:

```xml
<application tools:node="merge"
    android:fullBackupContent="@xml/steps_count_backup_rules"
    android:dataExtractionRules="@xml/steps_count_extraction_rules">
```

Both rule files exclude `step_count.db*` and all `steps_count_*` prefs from backup, cloud transfer, and device-to-device transfer. Runtime restore detection (2.3) is the third layer because Mi Mover / Clone Phone / EasyShare do not always honor rules.

### 2.3 Identity anchors

- `boot_count = Settings.Global.BOOT_COUNT` (API 24+): the boot-session oracle. Negative delta is demoted to "hub reset within a session" only (EC-49, EC-11).
- `device_id = Settings.Secure.ANDROID_ID`: cross-device restore/clone detector.
- `install_id` marker file: same-device clone/wholesale-copy detector.
- C's regression check: `boot_count` unchanged but `anchor.last_event_elapsed_ms > SystemClock.elapsedRealtime()` also means restored/cloned state (impossible in a live session).

Any mismatch: `health_log('restore_detected')`, delete anchor (baseline is radioactive), keep step rows (real history; uuids still dedupe server-side), first event re-anchors with **zero credit**. No phantom delta is arithmetically possible.

---

## 3. DB schema v3 and migration

### 3.1 Schema

```sql
CREATE TABLE steps (
    seq             INTEGER PRIMARY KEY AUTOINCREMENT,   -- monotone insert order; recommended sync cursor
    uuid            TEXT NOT NULL UNIQUE,                -- deterministic (3.3); dedupe key
    step_count      INTEGER NOT NULL CHECK (step_count > 0 AND step_count <= 100000),
    timestamp       INTEGER NOT NULL,                    -- interval END, epoch ms (v2 name kept: EC-60)
    start_timestamp INTEGER NOT NULL,                    -- interval START, epoch ms
    source          TEXT NOT NULL DEFAULT 'live',        -- 'live' | 'gap' | 'boot_gap' | 'legacy'
    flags           INTEGER NOT NULL DEFAULT 0,          -- 1 CLOCK_CLAMPED, 2 LOW_ACCURACY, 4 LEGACY_SUSPECT
    boot_count      INTEGER,                             -- forensics
    tz_offset_min   INTEGER,                             -- writing-zone offset (EC-36 future API)
    created_ts      INTEGER NOT NULL                     -- commit wall time (forensics)
);
CREATE INDEX idx_steps_end   ON steps(timestamp);
CREATE INDEX idx_steps_start ON steps(start_timestamp);

CREATE TABLE tracker_state (
    id                    INTEGER PRIMARY KEY CHECK (id = 1),
    device_id             TEXT NOT NULL,
    install_id            TEXT NOT NULL,
    boot_count            INTEGER NOT NULL,
    anchor_counter        REAL,          -- exact float from the HAL (EC-48); NULL = no anchor
    anchor_elapsed_ms     INTEGER,
    anchor_wall_ms        INTEGER,
    last_row_end_ms       INTEGER NOT NULL DEFAULT 0,   -- strictly-increasing watermark
    updated_wall_ms       INTEGER NOT NULL
);

CREATE TABLE health_log (                               -- ring, pruned to newest 500
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    wall_ms INTEGER NOT NULL, elapsed_ms INTEGER NOT NULL, boot_count INTEGER,
    type TEXT NOT NULL,       -- garbage_value | implausible_delta | quarantine_rebaseline | counter_reset
                              -- | reboot_recovered | restore_detected | db_corrupt | db_error | clock_jump
                              -- | fgs_denied | permission_state | sensor_silent_reregister | accuracy_change
                              -- | timezone_changed | migration | legacy_pending_discarded
    detail TEXT               -- raw values included (the spike-forensics layer)
);
```

`PRAGMA journal_mode=WAL; synchronous=NORMAL`. A lost WAL tail loses rows + anchor **together** (one transaction), which rolls the cursor back consistently: never a duplicate; folds into the reboot loss bound. The `step_count <= 100000` CHECK turns any future logic bug into a loud transaction failure instead of a 2-billion-step row.

### 3.2 Migration v2 -> v3 (and v1 -> v3)

Inside `onUpgrade` (helper transaction), **no catch blocks**: a throw rolls back, the version stays 2, migration retries on next open (fixes EC-22). `onCreate` likewise never catches; there is no DROP-and-recreate fallback. `onDowngrade` is overridden to a data-preserving no-op (EC-60).

1. Precondition: free space `>= 2.5 * dbFileSize + 1 MiB`, else `throw IOException` (retry later; never wipe).
2. `ALTER TABLE steps RENAME TO steps_v2;` create v3 tables.
3. Copy rows in Kotlin batches of 500 inside the same transaction:
   `start_timestamp = max(timestamp - 60_000, previous row's timestamp, localMidnightOf(timestamp))`, `timestamp = min(timestamp, migrationNow)` (clamp future-dated v2 rows, EC-25), `source='legacy'`, `flags = LEGACY_SUSPECT if step_count >= 20_000`, **same uuid** (already-synced rows stay deduplicated, EC-10). Verify row-count equality; throw on mismatch. `DROP TABLE steps_v2`.
4. Salvage a stranded `steps_old` table (v1->v2 fallback residue, EC-22) the same way, then drop it.
5. Seed `tracker_state`: current `device_id`/`boot_count`/`install_id`, `anchor_counter = NULL` (v2's prefs `last_sensor_value` is **deliberately discarded**: it is exactly the EC-2/EC-5/EC-7 phantom vector; the first event re-anchors with zero credit), `last_row_end_ms = MAX(timestamp)` (or 0).
6. First manager init after migration (outside `onUpgrade`): `moveDatabaseFrom`/`moveSharedPreferencesFrom` into DPS (no-ops thereafter); read legacy CE `pending_steps`: if `0 < p <= 5000`, insert one `source='gap'` row over `[last_row_end_ms, now]` (midnight-split); larger values go to `health_log('legacy_pending_discarded')`. Clear CE prefs. Write the install marker.

### 3.3 Deterministic UUIDs

`uuid = UUID.nameUUIDFromBytes("$deviceId|$bootCount|$counterStart|$counterEnd|$subIndex")` where `counterStart/End` is the anchor interval the row credits and `subIndex` numbers midnight splits. Rows are `INSERT OR IGNORE`; any pathological re-derivation dedupes locally and server-side.

### 3.4 Corruption

Custom `DatabaseErrorHandler`: close, **rename** to `step_count.db.corrupt-<epochms>` (keep newest 2; never delete), recreate, `health_log('db_corrupt')` on the fresh DB. The anchor died with the wiped DB, so the first event re-anchors with zero credit: no phantom (EC-21).

---

## 4. Core algorithm

### 4.1 Threading and DB lifecycle

- Dedicated `HandlerThread("steps")` for sensor delivery; all DB work on `Dispatchers.IO`; never the main thread (EC-39/EC-45/EC-51).
- One `stateMutex` serializes flush (single-flight), queries, export (EC-13/EC-53/EC-59).
- `StepCountDatabase` is a **process-wide singleton over the DPS path, opened lazily, never closed** until process death (kills EC-40; `cleanup()` no longer closes the DB). Serviceless reads use it directly (EC-30).

### 4.2 Constants

| Constant | Value | Rationale |
|---|---|---|
| `GARBAGE_ABS_MAX` | `1.0e9f` | 4 steps/s for 8 years of uptime; rejects uint32-as-float, `Float.MAX_VALUE`, `+Inf` pre-arithmetic; NaN/negative rejected by `isFinite() && v >= 0` (EC-1/EC-48). |
| `plausibleMax(dtSec)` | `60 + 5.0*min(dtSec, 3600) + 1.2*max(0, dtSec - 3600)` | Burst 5/s (above world cadence) for the first hour, ~104k/day sustained after. 1 min -> 360; 5 min FIFO -> 1,560; 1 h -> 18,060; 24 h -> ~117k; 10-day dead service with 42k real steps passes; 50k in a short gap is mathematically rejected (EC-3, ~1000x tighter than the flat 500,000). |
| `FLUSH_STEP_THRESHOLD` | 200 | ~1 row per 200 live steps. |
| `FLUSH_INTERVAL_MS` | 60,000, deadline on `elapsedRealtime`, **re-checked on every event delivery** | Suspend-proof (EC-33); during suspend nothing is in RAM to lose (hardware FIFO holds it). |
| `MAX_REPORT_LATENCY_US` | 300,000,000 (5 min) | Hardware FIFO batching; events keep true per-burst timestamps (EC-8, battery). |
| `SEGMENT_MERGE_GAP_MS` / `GAP_SOURCE_THRESHOLD_MS` | 300,000 (5 min) | Closer events merge into one attribution segment; a longer silent gap is booked as `source='gap'`. |
| `MAX_LIVE_ROW_SPAN_MS` | 30 min | Row coalescing ceiling (hourly charts stay honest). |
| `FUTURE_SLACK_MS` | 2,000 | Absolute backstop: no stored timestamp may exceed `now + 2s` (EC-25). |
| `CLOCK_JUMP_SLACK_MS` | 900,000 (15 min) | A forward wall jump beyond the anchor-projected monotonic time is clamped to the projection (EC-25); a new wall offset is adopted as baseline only after it stays stable >= 1 h (or a matching `ACTION_TIME_SET`/`TIMEZONE_CHANGED`), so a transient forward date-set self-heals. |
| `QUARANTINE_CONFIRM` | 3 mutually-consistent events AND 10 min | Persistent implausible counter -> re-anchor without credit. |
| `WATCHDOG_SILENCE_MS` | 6 h | Listener re-registration, driven off `lastProgressElapsed` (accepted positive deltas only), NEVER the zero-delta heartbeat, so a frozen-but-delivering hub cannot suppress it (EC-19/EC-38). |
| Sensor | `getDefaultSensor(TYPE_STEP_COUNTER, /*wakeUp=*/true)`, fallback non-wake; `SENSOR_DELAY_NORMAL` + latency above, on the HandlerThread | EC-8. Registration failure: retry 5 s / 60 s / 10 min, then degraded (EC-46). |

### 4.3 Per-event time attribution (EC-9, exact)

Recomputed fresh per event (never a cached offset):

```
elapsedMs = event.timestamp / 1_000_000
nowElapsed = SystemClock.elapsedRealtime()
if (elapsedMs !in 1..nowElapsed + 10_000) elapsedMs = nowElapsed      // bogus-HAL clamp
wallMs = System.currentTimeMillis() - nowElapsed + elapsedMs
// EC-25 forward gate: the monotonic elapsed clock, not the (possibly poisoned) wall clock, is the oracle.
expectedWall = anchor_wall_ms + (elapsedMs - anchor_elapsed_ms)       // where the anchor says we should be
if (anchored && wallMs > expectedWall + CLOCK_JUMP_SLACK_MS) wallMs = expectedWall   // clamp forward set
wallMs = min(wallMs, now + FUTURE_SLACK_MS)                           // absolute future backstop
wallMs = max(wallMs, watermark + 1)                                   // monotone floor (EC-24/EC-27)
clamped => row flag CLOCK_CLAMPED; |rawWall - wallMs| > 10 min => health_log('clock_jump')
```

The forward gate is what actually defeats EC-25: clamping to `now + 2s` alone is useless when the wall
clock itself is set 12 days forward, because `now` is already the poisoned value. Projecting from the
durable anchor over the monotonic (suspend-inclusive, adjustment-immune) elapsed delta pins each row to
the day it truly occurred, and the watermark still advances by at most `CLOCK_JUMP_SLACK_MS` even in the
worst manual date-set, so the legacy `timestamp > lastSync` cursor and day-attribution both stay correct.

Each event in a FIFO burst carries its own `event.timestamp`, so a screen-off afternoon walk delivered at unlock is reconstructed across the afternoon, not collapsed to "now" (EC-8). Gating dt always uses the monotonic `elapsedMs` delta, immune to NITZ flapping (EC-24).

### 4.4 State machine

```
NO_ANCHOR         anchor NULL or session invalidated (fresh install, restore, corruption recovery)
NO_ANCHOR_REBOOT  boot_count changed; resolved at first event (4.6)
ANCHORED          anchor valid for current (device_id, install_id, boot_count)
QUARANTINE        ANCHORED + last delta rejected; anchor FROZEN, awaiting confirmation

service start (reconciliation, no writes before evidence):
  install_id/device_id mismatch, boot_count regressed,
  or same boot_count with anchor_elapsed > now_elapsed  -> restore_detected -> NO_ANCHOR
  boot_count increased                                  -> NO_ANCHOR_REBOOT
  anchor NULL                                           -> NO_ANCHOR
  else                                                  -> ANCHORED

NO_ANCHOR   + first valid event  -> commit anchor := v (credit 0) -> ANCHORED
ANCHORED    + valid delta        -> accumulate segment; flush advances anchor transactionally
ANCHORED    + delta < 0          -> hub reset (EC-49): pre-flush delivered steps, then credit
                                    gate(v) as 'gap' over [lastEventWall, now], re-anchor := v
ANCHORED    + garbage value      -> discard, log; anchor and baseline UNTOUCHED (EC-1)
ANCHORED    + implausible delta  -> QUARANTINE (anchor frozen, event discarded, logged)
QUARANTINE  + next event plausible vs frozen anchor         -> ANCHORED (glitch was transient)
QUARANTINE  + same excess becomes plausible for larger dt   -> accept (self-healing late credit)
QUARANTINE  + 3 consistent implausible events over >= 10 min -> re-anchor := v, credit 0
```

Key property: **rejection never moves the anchor.** A single garbage value can neither credit steps nor destroy the baseline; "add the cap as real steps" is deleted.

### 4.5 Accumulation and segments (in memory, non-authoritative)

Each accepted event extends the last segment (gap < 5 min) or opens a new one `(counterStart, counterEnd, wallStart, wallEnd, flags)`. A segment whose preceding silence exceeds 5 min is tagged `gap`, with `wallStart` projected backward on the **monotonic** clock (`wallEnd - (elapsedMs - prevElapsedMs)`), immune to wall-clock changes during the gap (EC-27). Segments only shape attribution; **credit is always derived from `counter - anchor`**, so any segment bug can misplace steps in time but never change the total (A's invariant, adopted verbatim).

### 4.6 Reboot recovery (boot_count changed; first event decides)

- **Case A, `v < anchor_counter`** (reset-on-boot hub, the common case): `v` is the real step count since boot. Credit `min(v, plausibleMax(elapsedMs/1000))` as one `source='boot_gap'` interval `[max(anchor_wall_ms, bootWall), now]`, midnight-split; else credit 0 (garbage guard). Recovers the boot-to-first-event steps the old code silently discarded (EC-11).
- **Case B, `v >= anchor_counter`** (persistent hub, Samsung-style): credit `v - anchor_counter` gated by `plausibleMax(wallGap)` as `source='gap'` over `[anchor_wall_ms, now]`; implausible -> credit 0, diag.
- Either way, rows + full anchor rewrite (new boot_count) commit in one transaction.

### 4.7 Flush (single-flight under `stateMutex`)

Triggers: unflushed >= 200; 60 s elapsed-deadline (timer or event-driven re-check); hub-reset pre-flush; boot catch-up; best-effort on stop.

```
credit = floor(lastEvent.counter - anchorMirror.counter);  if credit <= 0 return
rows = buildRows(segments)      -- merge (< 5 min gaps, <= 30 min span), split at local midnights
                                -- (end = midnight - 1 ms, steps apportioned by duration,
                                --  largest-remainder), force sum == credit (residue to last row),
                                -- deterministic uuids, strictly-increasing timestamps vs watermark
BEGIN IMMEDIATE;
  INSERT OR IGNORE rows;
  UPDATE tracker_state SET anchor_counter=lastEvent.counter, anchor_elapsed_ms, anchor_wall_ms,
         last_row_end_ms=rows.last.timestamp, boot_count=cur, device_id=cur, install_id=cur;
COMMIT;                                          -- throws on ANY failure -> nothing changes
anchorMirror = lastEvent; prune segments with counterEnd <= anchor
runCatching { onFlushSuccess() }                 -- notification OUTSIDE the accounting path (EC-14)
```

Failure path: transaction rolls back, anchor frozen, segments retained, reopen backoff 1/5/30 min, error surfaced via `health_log` + Dart status. There is no restore-into-buffer code and no deferral loop: EC-4/EC-6/EC-14 are removed mechanically. Because no row crosses local midnight at write time, day queries are trust-exact (EC-32/EC-54/EC-55 write side).

---

## 5. Crash-safety argument and loss bounds

`A` = durable anchor, `C` = hardware counter, `M` = memory. Invariants: **I1** rows count exactly the counter range `(session start, A]`; **I2** `M` is reconstructible from `(C, A)`.

| # | Kill point | Outcome |
|---|---|---|
| 1 | During `onSensorChanged`, any position | A unchanged; next event delta `= C - A` re-derives everything. 0 loss, 0 dup. |
| 2 | After segment append, before flush | Same as 1: segments were a pure cache of `C - A`. |
| 3 | Inside the transaction, pre-COMMIT | Rollback; same as 1. |
| 4 | Post-COMMIT, pre-mirror/prune | Rows + A durable together; restart re-derives only post-flush steps. 0 dup. |
| 5 | Prefs interleavings | **Do not exist**: prefs hold no counting state. |
| 6 | Query concurrent with flush | Shares `stateMutex`: sees either (old rows + live segments) or (new rows + pruned segments); both sum identically. No dip (EC-53). |
| 7 | Power loss losing the WAL tail | Rows + A vanish together; cursor rolls back consistently; folds into the reboot bound. Never a duplicate. |
| 8 | Kill during stop/cleanup | Final flush is best-effort (3 s timeout, off-main); skipping it loses nothing durable (case 1 applies). EC-39 ANR removed. |
| 9 | Straggler flush after stop | All flushes in one scope cancelled-and-joined; DB never closed anyway; notification updates gated on liveness (EC-40). |

**Bounds:** double-count 0 always (deterministic uuids make even hypothetical re-derivations idempotent). Loss within a living boot session 0, regardless of kills or DB failures. Loss on reboot/power loss/hub reset: steps since the last committed anchor plus the undelivered hardware FIFO tail: typical `<= plausibleMax(60 s) = 360`, absolute worst `~ (60 s + 5 min) * 5/s ≈ 1,800` steps; clean shutdowns near 0 via the final flush. Boot-to-first-event steps are **recovered** (4.6 Case A), not lost. Rate-gate discards are the only intentional loss and every one is diag-logged with raw values.

---

## 6. Queries, day boundaries, DST

- **Serviceless reads** (EC-30): singleton DPS DB; `Dispatchers.IO`, reply on main (EC-45); real errors surface as `result.error`, never fabricated zeros. Args coerced `(argument<Number>)?.toLong()` (EC-44).
- **Range sum** over half-open `[qs, qe)` (legacy inclusive end adapted as `end + 1`): rows fully inside contribute whole; rows straddling (only possible after a later zone change) contribute proportionally to overlap, largest-remainder rounding (EC-36 honest best estimate). Live contribution = in-memory segments overlapping the window, per-segment (EC-31: a 5-minute range gets 5 minutes of pending, never the whole buffer).
- **Today** (EC-34/EC-55): ONE `ZonedDateTime.now(zone)` snapshot yields `[date.atStartOfDay(zone), date.plusDays(1).atStartOfDay(zone))`; `atStartOfDay` resolves spring-forward gaps; half-open windows tile fall-back exactly. Day totals always equal the timeline sum over the same window (reconciliation property).
- **Timeline**: legacy keys `uuid`, `step_count`, `timestamp` (= end) preserved; additive `start_timestamp`, `source`, `flags`, `is_estimated`, `seq`.
- **Sync**: legacy `getTimelineAfter(ts)` = `WHERE timestamp > ?` is now **structurally safe**: the watermark invariant makes `timestamp` strictly increasing across all rows ever written (EC-24), and the `now + 2s` write clamp bounds watermark poisoning to 2 seconds (EC-25). Additive `getTimelineAfter(lastSeq)` paging by `seq` is the documented recommended cursor, immune to every clock pathology.
- **TimeStampUtils**: LOCAL/UTC converters stay identities; docs corrected to "all timestamps are epoch milliseconds"; no value shifting ever (EC-47).

---

## 7. Service / receiver lifecycle hardening

### 7.1 Plugin manifest (EC-43)

Declares (merged into every consumer): `ACTIVITY_RECOGNITION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_HEALTH`, `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS`; `<uses-feature android:name="android.hardware.sensor.stepcounter" android:required="false"/>`; the `<service>` (`directBootAware="true"`, `foregroundServiceType="health"`, exported false); the `<receiver>` with only deliverable actions: `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` (single filter, no data scheme), `QUICKBOOT_POWERON`; backup rules (2.2). Dead `USER_UNLOCKED`/`USER_PRESENT` filters deleted (EC-26).

### 7.2 Service correctness

- `startForeground()` called first in `onCreate` **and** for every `onStartCommand`, before any branch or early return (EC-20). `SecurityException` (API 34+ health-FGS without ACTIVITY_RECOGNITION) -> `health_log('fgs_denied')`, `last_start_failure` set, clean `stopSelf()`, `START_NOT_STICKY` for that command: no boot crash-loop (EC-18).
- `ensureRunning()` is idempotent and re-invoked on every start command and flush tick: creates the manager if null (retry with backoff, EC-15), registers the sensor if unregistered. `isRunning` is derived (`manager != null && sensorRegistered && foregroundStarted`), reset unconditionally in `onDestroy`; the stop/start race cannot wedge (EC-16).
- Permission checked explicitly (`registerListener` lies on API 29+, EC-17); missing -> degraded state visible in `getTrackingStatus()`; on re-grant the downtime becomes one gated `gap` row.
- Stop protocol (EC-58): Dart sets `tracking_enabled=false` FIRST, then `startService(FORCE_STOP)` with `context.stopService()` fallback on `IllegalStateException`; `START_NOT_STICKY` after stop; a stop of a stopped service creates nothing and there is no pending buffer to fabricate rows from. Boot receiver and `MY_PACKAGE_REPLACED` honor the flag (EC-42).
- Return `START_STICKY` normally; null-intent restart consults `tracking_enabled`.

### 7.3 Resurrection ladder (C's, adopted whole)

- Boot receiver: check `tracking_enabled` (DPS prefs, pre-unlock readable), start FGS, and always arm one inexact `AlarmManager.set(ELAPSED_REALTIME, +60 s)` retry PendingIntent, cancelled by the service on reaching RUNNING. No `postDelayed` in a dying process (EC-28).
- Persisted `JobScheduler` periodic job (6 h, `setPersisted(true)`): if `tracking_enabled && !running`, attempt `startForegroundService`; on Android 12+ `ForegroundServiceStartNotAllowedException`, post a "Step tracking stopped: tap to resume" notification whose tap launches the app (an allowed FGS start path). Honest degraded mode for OEM kills (EC-23, EC-41).
- `onTaskRemoved`: schedule an immediate one-shot job/alarm restart (EC-41).
- Unlock recovery: runtime-registered `ACTION_USER_UNLOCKED` receiver inside the already-running direct-boot service (manifest delivery is dead, EC-26); mostly moot since DPS makes pre-unlock counting fully functional (EC-29).
- Sensor watchdog: each flush tick, if `elapsedRealtime - lastProgressElapsed > 6 h` with sensor + permission present -> unregister/re-register. The clock is `lastProgressElapsed` (advanced only by accepted positive deltas), never the zero-delta heartbeat, so a MIUI-style hub that keeps re-emitting the same frozen value can no longer suppress the watchdog. On trip, if zero-delta events were arriving in the window (`sawZeroDeltaSinceProgress`), the hub is frozen-but-delivering -> `health_log('sensor_frozen_suspect', stuckValue)` (repeat rows suppressed to protect the 500-entry ring); otherwise it was truly silent -> `health_log('sensor_silent_reregister')` (EC-19/EC-38). `onAccuracyChanged` logged; UNRELIABLE windows flag rows `LOW_ACCURACY` (EC-50).
- Notification: refreshed on flush and on a context-registered `ACTION_SCREEN_ON` (EC-35); content from the single-snapshot day window (EC-34); `POST_NOTIFICATIONS` state surfaced (EC-41).
- Engine detach clears the static `stepCountChannel` when it references the detaching engine (EC-57); channel invokes throttled to 1/s.
- Export (EC-59): under `stateMutex`: flush, then `VACUUM INTO` on API 30+, else `wal_checkpoint(TRUNCATE)` + copy db/-wal/-shm; `PRAGMA integrity_check` on the copy; fail loudly. Export includes `health_log` (that is the point).

### 7.4 Degraded modes

| Condition | Behavior |
|---|---|
| No TYPE_STEP_COUNTER / registration fails after retries | Service stops itself; `getTrackingStatus` reports `no_sensor`; reads still serve the DB (EC-46). |
| Permission missing | No FGS crash-loop; status API reports; re-grant downtime -> one gated `gap` row (EC-17/18). |
| DB broken | Anchor frozen; steps keep accruing in the hardware counter; booked when DB recovers within the boot session; reopen backoff (EC-4). |
| Force-stop / OEM kill | Framework limit acknowledged (EC-23); ladder (7.3) + gap recovery bound the damage; outage visible and marked. |
| Pre-unlock boot | Fully functional: DPS + directBootAware service count from `LOCKED_BOOT_COMPLETED` (EC-29). |

### 7.5 Additive Dart API

`getTrackingStatus() -> {state, serviceRunning, sensorAvailable, permissionGranted, notificationsGranted, lastEventAgeMs, lastProgressAgeMs, dbOk, trackingEnabled, lastError}` (`lastProgressAgeMs` surfaces a frozen-but-delivering hub, EC-38); `getTimelineAfter(lastSeq)` variant; timeline maps gain `seq`, `start_timestamp`, `source`, `flags`, `is_estimated`. Existing signatures unchanged.

---

## 8. Coverage map

| ID | Mechanism |
|---|---|
| EC-1 | `isFinite && 0 <= v <= 1e9` gate pre-arithmetic; garbage discarded with anchor AND baseline untouched; implausible deltas quarantine against a frozen anchor; cap-and-add deleted. |
| EC-2 | DPS storage (never backed up) + manifest backup rules + device_id/install_id/boot regression checks -> anchor voided, first event credits 0. |
| EC-3 | `plausibleMax` burst+sustained envelope on monotonic per-event dt; implausible = quarantine/discard, never record. |
| EC-4 | Insert failures throw; transaction rolls back rows+anchor together; steps stay recoverable in the counter; reopen backoff; error surfaced. Failure means retry, never consume-without-store. |
| EC-5 | Prefs removed from counting; baseline (anchor) advances only atomically with the rows it credits. |
| EC-6 | No prefs pending, no 50k deferral loop; large catch-ups are single midnight-split interval rows; nothing stale to restore. |
| EC-7 | Same-boot downtime delta booked as `source='gap'` over `[anchor wall, now]`, gated, midnight-split; never stamped "now". |
| EC-8 | Wake-up sensor + 5 min maxReportLatency + per-event timestamps reconstruct screen-off walks at true times; pre-midnight walks stay on their day. |
| EC-9 | Per-event `wallMs = curTimeMillis - elapsedRealtime + event.ts/1e6`, recomputed each event, clamped to `(watermark, now+2s]`. |
| EC-10 | Backup excluded; migration preserves v2 uuids; deterministic uuids from (device, boot, counter range) make any re-derivation dedupe server-side; restore voids the anchor. |
| EC-11 | BOOT_COUNT session marker; Case A credits `min(v, gate)` as `boot_gap` from boot time; Case B gates by wall gap; no silent discard, no lump-at-now. |
| EC-12 | Unflushed steps derived from `counter - anchor`; process death loses only memory, re-derived at next event; loss only at boot-session end, bounded Section 5. |
| EC-13 | Single-flight flush under `stateMutex`; no prefs pending to race on. |
| EC-14 | `onFlushSuccess()` outside the accounting try; commit is the success point; no restore-into-buffer path exists. |
| EC-15 | `ensureRunning()` retried per start command/tick; failure -> degraded status, not zombie; DPS removes the pre-unlock CE crash cause. |
| EC-16 | Idempotent re-init on every start; `isRunning` derived, reset unconditionally in `onDestroy`. |
| EC-17 | Explicit permission check + status API + silence watchdog; re-grant catch-up = gated, marked `gap` row. |
| EC-18 | SecurityException caught -> `fgs_denied` diag + resume notification + clean stop, `START_NOT_STICKY`; plugin surfaces `last_start_failure`. |
| EC-19 | 6 h silence watchdog re-registers; eventual burst is per-event-attributed or marked `gap`, never a "now" lump. |
| EC-20 | `startForeground()` unconditionally in `onCreate` and per `onStartCommand`, answering every `startForegroundService` promise. |
| EC-21 | Custom DatabaseErrorHandler renames (never deletes) the corrupt file; recovery logged and surfaced. |
| EC-22 | No catches in `onCreate`/`onUpgrade` (rollback + retry), free-space precheck, no DROP fallback, `steps_old` salvage. |
| EC-23 | Framework limit documented; onTaskRemoved job + persisted JobScheduler watchdog + resume notification + gap recovery bound and mark the outage. |
| EC-24 | Strictly-increasing watermark: backward clocks compress at `watermark+1` (flagged), never backdate; legacy `> lastSync` cursor provably never starves; monotonic dt for gating; `seq` cursor immune entirely. |
| EC-25 | Forward wall jumps clamped to the anchor-projected monotonic time (`expectedWall + 15 min`), NOT to the poisoned wall clock, so a multi-day date-set forward no longer poisons the watermark or day attribution; the new offset is adopted only after it stays stable >= 1 h (transient set self-heals); `now + 2s` absolute backstop retained; migration clamps existing future v2 rows; `seq` cursor unaffected. |
| EC-26 | Dead manifest unlock filters removed; runtime `USER_UNLOCKED` receiver in the direct-boot service; broken data-scheme `MY_PACKAGE_REPLACED` filter deleted, working one kept. |
| EC-27 | Gap starts projected on elapsedRealtime; pre-NITZ stamps clamp to `watermark+1` flagged CLOCK_CLAMPED; nothing lands in 1970. |
| EC-28 | AlarmManager retry PendingIntent (survives process death) replaces `postDelayed`; cancelled by the service on success. |
| EC-29 | Service directBootAware + DB/prefs in DPS: pre-unlock counting fully works from `LOCKED_BOOT_COMPLETED`; no CE crash. |
| EC-30 | Never-closed singleton DPS DB serves all reads when the service is down; errors surface as errors, not zeros. |
| EC-31 | Live contribution = timestamped segments overlapping the queried window only; narrow ranges get only their share. |
| EC-32 | No persisted pending buffer; rows midnight-split at write; gap rows carry their true interval, never flush time. |
| EC-33 | Flush deadline on elapsedRealtime, re-checked at every event delivery; stamps come from event timestamps, so delay drift is correctness-neutral anyway. |
| EC-34 | Day window from one `ZonedDateTime.now` snapshot, half-open. |
| EC-35 | Notification refreshed on flush and on SCREEN_ON; day window recomputed at display time. |
| EC-36 | Epoch-pure rows + proportional overlap re-bucket honestly under a new zone; `tz_offset_min` recorded per row for a future written-zone API; `health_log('timezone_changed')`. Documented. |
| EC-37 | Hardware floor documented; rate gate clips only >5/s bursts; per-event cadence in health_log enables future heuristics. |
| EC-38 | Watchdog driven off `lastProgressElapsed` (accepted positive deltas only); zero-delta heartbeats from a frozen-but-delivering hub do NOT re-arm it, so 6 h without real progress trips unregister/re-register and logs `sensor_frozen_suspect` with the stuck value; `getTrackingStatus` exposes `lastProgressAgeMs`. Truly-silent connections covered as before. Steps lost inside a hub-freeze window (or while powered off) remain physically unrecoverable: documented limit, not a fixable gap. |
| EC-39 | Cleanup is a bounded 3 s best-effort flush on IO; skipping it is provably safe (recovery re-derives); no main-thread runBlocking. |
| EC-40 | DB never closed; one scope cancelled-and-joined; flush under `stateMutex`; notification updates gated on liveness. Straggler class deleted. |
| EC-41 | onTaskRemoved restart job; `notificationsGranted` in `getTrackingStatus`; resume-notification path. |
| EC-42 | `tracking_enabled` in DPS prefs gates receiver, watchdog job, alarms, and STICKY restarts. |
| EC-43 | Service, receiver, permissions, uses-feature, and backup rules ship in the plugin manifest and merge into every consumer. |
| EC-44 | `(argument<Number>)?.toLong()` coercion in all handlers. |
| EC-45 | All handlers dispatch DB work to `Dispatchers.IO`, reply on main; `seq` paging enables chunked sync. |
| EC-46 | Null sensor / register-false retried with backoff then service self-stops with `no_sensor`; status API distinguishes "no hardware" from "no steps"; uses-feature declared. |
| EC-47 | Converters stay identities; docs corrected to "epoch ms everywhere"; no shifting "fix" ever permitted. |
| EC-48 | Anchor stores the exact float as REAL; Double delta math; NaN/Inf rejected explicitly pre-math; sub-2^24 quantization lumpiness absorbed by interval attribution (benign). |
| EC-49 | Reboot = BOOT_COUNT change only; same-boot negative delta = hub reset: pre-flush delivered steps, credit gated `v` as `gap`, re-anchor; persistent hubs via Case B. |
| EC-50 | `onAccuracyChanged` logged; UNRELIABLE-window rows flagged LOW_ACCURACY; jumps still rate-gated. |
| EC-51 | Zero delta = heartbeat, memory-only; events on a HandlerThread; no per-event prefs writes exist; channel invokes throttled. |
| EC-52 | Pending cap and get-then-add arithmetic deleted; credit derives from counter arithmetic in one place under the mutex. |
| EC-53 | Readers share `stateMutex` with flush; totals are monotone through a flush. |
| EC-54 | Midnight-split rows end at `midnight - 1 ms`; strictly-increasing timestamps; half-open internal windows; boundary rows satisfy exactly one window; half-open chunking documented. |
| EC-55 | `atStartOfDay(zone)` half-open windows tile fall-back and spring-forward exactly; write-time splitting means no row straddles the anomalous hour. |
| EC-56 | Documented limitation (one physical counter, per-profile instances); `boot_count` + `install_id` in rows let a backend detect same-device duplicates. |
| EC-57 | Static channel cleared on matching engine detach. |
| EC-58 | Flag-first stop + `stopService()` fallback + `START_NOT_STICKY` after stop; a stopped service cannot fabricate rows (no pending buffer exists). |
| EC-59 | Export under `stateMutex`: VACUUM INTO (API 30+) or checkpoint + db/-wal/-shm copy, integrity-checked; failures loud. |
| EC-60 | `onDowngrade` no-op preserving data; v3 keeps the `timestamp` column name so older readers still see rows. |

---

## 9. Pseudocode (Kotlin-like)

### 9.1 onSensorChanged (dedicated HandlerThread)

```kotlin
fun onSensorChanged(event: SensorEvent) {
    val v = event.values[0]
    if (!v.isFinite() || v < 0f || v > GARBAGE_ABS_MAX) {
        healthLog("garbage_value", "raw=$v"); return              // EC-1: no credit, baseline untouched
    }
    val nowElapsed = SystemClock.elapsedRealtime()
    val evElapsed = event.timestamp / 1_000_000
    val elapsedMs = if (evElapsed in 1..nowElapsed + 10_000) evElapsed else nowElapsed
    var wallMs = System.currentTimeMillis() - nowElapsed + elapsedMs         // EC-9, per event
    val rawWall = wallMs
    val expectedWall = if (anchorMirror != null)                            // EC-25: monotonic oracle
        anchorMirror.wallMs + (elapsedMs - anchorMirror.elapsedMs) else wallMs
    if (anchorMirror != null && wallMs > expectedWall + CLOCK_JUMP_SLACK_MS)
        wallMs = expectedWall                                               // clamp forward date-set
    wallMs = min(wallMs, System.currentTimeMillis() + FUTURE_SLACK_MS)       // absolute backstop
    wallMs = max(wallMs, watermark() + 1)                                    // EC-24/EC-27
    if (abs(rawWall - wallMs) > 600_000) healthLog("clock_jump", "shift=${rawWall - wallMs}")
    val clamped = wallMs != rawWall

    when (state) {
        NO_ANCHOR        -> { commitAnchorOnly(v, elapsedMs, wallMs); state = ANCHORED; return }
        NO_ANCHOR_REBOOT -> { handleBootCatchup(v, elapsedMs, wallMs); return }          // 4.6
        QUARANTINE       -> if (handleQuarantine(v, elapsedMs, wallMs)) return           // 4.4
        ANCHORED         -> {}
    }
    val ref = lastEvent ?: anchorMirror.toEventRef()
    val delta = v.toDouble() - ref.counter

    if (delta < -0.5) {                                           // same-boot hub reset (EC-49)
        runBlockingBounded { flush() }                            // book everything already delivered
        healthLog("counter_reset", "old=${ref.counter} new=$v")
        val credit = min(v.toLong(), plausibleMax((wallMs - ref.wallMs) / 1000))
        if (credit > 0) enqueueGapSegment(credit, ref.wallMs, wallMs)        // steps since hub restart
        commitAnchorOnly(v, elapsedMs, wallMs); lastEvent = EventRef(v, elapsedMs, wallMs); return
    }
    if (delta < 0.5) {                                           // EC-51 heartbeat / EC-38 frozen hub
        lastEvent = EventRef(v, elapsedMs, wallMs)
        lastAnyEventElapsed = nowElapsed; sawZeroDeltaSinceProgress = true   // does NOT touch progress clock,
        return                                                   // so a frozen-but-delivering hub still trips
    }                                                            // the silence watchdog

    val dtSec = max(1L, (elapsedMs - ref.elapsedMs) / 1000)
    if (delta > plausibleMax(dtSec)) {                            // EC-1/EC-3: quarantine, anchor FROZEN
        enterQuarantine(v, elapsedMs); healthLog("implausible_delta", "d=$delta dt=$dtSec raw=$v"); return
    }
    appendSegment(ref, v, wallMs, dtSec,                          // opens 'gap' segment if silence > 5 min,
        flags = (CLOCK_CLAMPED if clamped) or                     // start projected back on elapsed clock
                (LOW_ACCURACY if event.accuracy == SENSOR_STATUS_UNRELIABLE))
    lastEvent = EventRef(v, elapsedMs, wallMs)
    lastProgressElapsed = nowElapsed                              // EC-38: watchdog clock, only on real progress
    lastAnyEventElapsed = nowElapsed; sawZeroDeltaSinceProgress = false
    if (v - anchorMirror.counter >= FLUSH_STEP_THRESHOLD ||
        nowElapsed - lastFlushElapsed >= FLUSH_INTERVAL_MS) scope.launch { flush() }   // EC-33
    throttledChannelInvoke()
}
```

### 9.2 flush (only writer; `Dispatchers.IO`)

```kotlin
suspend fun flush() = stateMutex.withLock {
    lastFlushElapsed = SystemClock.elapsedRealtime()
    val last = lastEvent ?: return
    val credit = floor(last.counter.toDouble() - anchorMirror.counter.toDouble()).toLong()
    if (credit <= 0) return

    val rows = buildRows(segments, anchorMirror, last, credit)
    // merge (< 5 min gaps, <= 30 min span), split at local midnights (end = midnight - 1 ms),
    // apportion by duration with largest-remainder, force sum(step_count) == credit (residue to last),
    // strictly-increasing timestamps vs watermark, uuid = nameUUID("$dev|$boot|$cStart|$cEnd|$i")
    try {
        db.transaction {                                          // BEGIN IMMEDIATE ... COMMIT
            rows.forEach { insertOrIgnore(it) }
            updateTrackerState(anchorCounter = last.counter, anchorElapsedMs = last.elapsedMs,
                anchorWallMs = last.wallMs, lastRowEndMs = rows.last().timestamp,
                bootCount = currentBootCount, deviceId = currentDeviceId, installId = installId)
        }
    } catch (e: Exception) {                                      // EC-4: rollback, anchor frozen,
        healthLogOnRecover("db_error", e); scheduleReopenBackoff(); return   // segments retained, retry
    }
    anchorMirror = last; watermarkCache = rows.last().timestamp
    segments.removeAll { it.counterEnd <= last.counter }
    runCatching { onFlushSuccess() }                              // notification outside accounting (EC-14)
}
```

### 9.3 Recovery on start + boot catch-up

```kotlin
fun recoverOnStart(ctx: Context) {
    val dps = ctx.createDeviceProtectedStorageContext()
    if (!flags.movedToDps) { dps.moveDatabaseFrom(ctx, DB_NAME)
                             dps.moveSharedPreferencesFrom(ctx, LEGACY_PREFS); flags.movedToDps = true }
    db = StepCountDatabase.singleton(dps)                         // corruption handler 3.4; never closed
    val marker = readOrCreateMarker(ctx.noBackupFilesDir)         // layer-3 restore detection (B)
    val st = db.readTrackerState()
    val boot = Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT)
    val dev = Settings.Secure.getString(cr, Settings.Secure.ANDROID_ID)
    state = when {
        st?.anchorCounter == null -> NO_ANCHOR
        st.deviceId != dev || st.installId != marker ||
        boot < st.bootCount ||
        (boot == st.bootCount && st.anchorElapsedMs > SystemClock.elapsedRealtime()) -> {
            healthLog("restore_detected"); db.deleteAnchor(); NO_ANCHOR }    // EC-2/EC-10
        boot > st.bootCount -> { pendingReboot = st; NO_ANCHOR_REBOOT }      // resolved at first event
        else -> { anchorMirror = st.toEventRef(); watermarkCache = st.lastRowEndMs; ANCHORED }
    }
    registerWakeupStepSensor(handlerThread, maxReportLatencyUs = 300_000_000)  // retry ladder on failure
    startFlushTickerAndWatchdog()
}

fun handleBootCatchup(v: Float, elapsedMs: Long, wallMs: Long) {  // NO_ANCHOR_REBOOT (4.6)
    val st = pendingReboot!!; val bootWall = wallMs - elapsedMs
    val (credit, start, src) =
        if (v < st.anchorCounter)                                 // Case A: reset-on-boot hub
            Triple(min(v.toLong(), plausibleMax(elapsedMs / 1000)),
                   max(st.anchorWallMs, min(bootWall, wallMs - 1)), "boot_gap")
        else {                                                    // Case B: persistent hub
            val d = floor(v - st.anchorCounter).toLong()
            val gapSec = max(1L, (wallMs - st.anchorWallMs) / 1000)
            Triple(if (d <= plausibleMax(gapSec)) d else 0L, st.anchorWallMs, "gap") }
    db.transaction {
        if (credit > 0) insertRows(splitAtMidnights(credit, start, wallMs, src))
        else if (v > 0) healthLog("implausible_delta", "boot catchup rejected raw=$v")
        updateTrackerState(anchorCounter = v, bootCount = currentBootCount, ...)
    }
    healthLog("reboot_recovered", "case=${src} credit=$credit")
    anchorMirror = EventRef(v, elapsedMs, wallMs); lastEvent = anchorMirror.toEventRef()
    state = ANCHORED
}
```

### 9.4 Query paths

```kotlin
suspend fun rangeSum(qs: Long, qeExcl: Long): Int = onIO { stateMutex.withLock {
    var total = 0L
    db.rowsOverlapping(qs, qeExcl).forEach { r ->                 // WHERE timestamp >= qs AND start < qeExcl
        total += if (r.start >= qs && r.end < qeExcl) r.count.toLong()
                 else apportion(r, qs, qeExcl)                    // straddlers only post-tz-change (EC-36)
    }
    total += segments.sumOf { seg -> apportionSeg(seg, qs, qeExcl) }   // EC-31: window-scoped live share
    total.toInt()
}}

fun todaysCount(): Int {
    val z = ZoneId.systemDefault(); val today = ZonedDateTime.now(z).toLocalDate()   // ONE snapshot (EC-34)
    return rangeSum(today.atStartOfDay(z).toInstant().toEpochMilli(),
                    today.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli())    // DST-exact (EC-55)
}

fun timelineAfter(watermarkTs: Long?, afterSeq: Long?) = onIO {
    val rows = when {
        afterSeq != null    -> db.query("seq > ?", afterSeq)                 // recommended cursor
        watermarkTs != null -> db.query("timestamp > ?", watermarkTs)        // safe: watermark invariant
        else                -> db.query(null)
    }
    rows.map { mapOf("uuid" to it.uuid, "step_count" to it.count,
                     "timestamp" to it.end,                                  // legacy key preserved
                     "start_timestamp" to it.start, "source" to it.source,
                     "flags" to it.flags, "is_estimated" to it.isEstimated, "seq" to it.seq) }
}
// legacy getStepCount(start, endInclusive) adapter: rangeSum(start ?: 0, (endInclusive ?: MAX-1) + 1)
// all Dart args coerced via (argument<Number>)?.toLong()                    // EC-44
```

---

## 10. What this buys

The field signature (single 1K-50K rows stamped "now") has four independent generators: garbage cap-and-add (EC-1/EC-3), backup-restored baselines (EC-2/EC-10), prefs/DB durability races (EC-5/6/12/13/14), and time-collapsed real catch-up (EC-7/8). This spec deletes each class structurally: implausible values quarantine against a frozen anchor and are never credited; baselines live in never-backed-up device-protected storage, triple-validated by boot_count + ANDROID_ID + install marker; the anchor-in-transaction protocol makes double counting impossible and bounds loss to one flush window per boot-session end (typically under 360 steps, quantified in Section 5); and per-event timestamps plus midnight-split interval rows with explicit `gap`/`boot_gap` markers land every step in the window it happened in, or label it honestly as an estimate. Residual, documented ceilings of a sensor-only design: hardware vibration miscounts (EC-37), frozen-hub silence detection-only (EC-38), force-stop gaps (EC-23), multi-profile duplication (EC-56), zone-change re-bucketing (EC-36).

---

## 11. Phased implementation plan

Five phases, dependency-ordered. Phases 1 to 2 stop the bleeding and can ship as a patch; phase 3 is the accuracy core and needs the schema migration; phases 4 to 5 harden and expose. Each phase is independently shippable and testable.

### Phase 0: Safety net (no behavior change, ~0.5 day)
- Add the `health_log` table (v2 -> v2.5 additive, no row rewrite) and start recording `garbage_value` / `implausible_delta` / `counter_reset` with raw values against the CURRENT code. This gives forensic evidence from real user devices while the rest is built, and confirms which generator dominates your field reports before you have finished fixing them.
- Ship backup-exclusion rules (2.2) and the plugin manifest declarations (7.1) immediately: pure win, no code risk, fixes EC-2 (restore vector) and EC-43 (dead-by-default integrators) on their own.

### Phase 1: Kill the phantom-spike generators (the "make it trustworthy fast" phase, ~2 days)
Smallest change set that removes the 40-50K reports, all inside `StepCountManager.kt`:
- Garbage gate `isFinite && 0 <= v <= 1e9` BEFORE arithmetic; delete cap-and-add (EC-1/EC-48).
- Boot-session anchoring via `Settings.Global.BOOT_COUNT` + `ANDROID_ID`: on mismatch, void baseline, re-anchor with zero credit (EC-2/EC-11).
- Rate gate `plausibleMax(dtSec)` replacing `MAX_REASONABLE_DELTA`, using per-event `event.timestamp` for dt (EC-3). This requires forwarding `event.timestamp` from `BackgroundServiceManager.onSensorChanged` (EC-9), the one change outside the manager.
- Fix the insert-failure blind spot (propagate, do not swallow) and delete the double-restore in the flush catch (EC-4/EC-14).
This phase alone is defensible as a `0.0.4` release: it stops fabrication without the migration risk of the interval schema.

### Phase 2: Durability protocol (~2 days)
- Move the DB and prefs to device-protected storage via `moveDatabaseFrom`/`moveSharedPreferencesFrom`, guarded by the `moved_to_dps` marker (EC-2/EC-29).
- Make `StepCountDatabase` a never-closed process singleton; remove `runBlocking` cleanup from the main thread; single-flight flush under `stateMutex` (EC-39/EC-40/EC-13).
- Replace prefs-based pending with the derived `unflushed = counter - anchor` model and anchor-in-transaction (EC-5/EC-6/EC-12). After this phase, double-count is provably impossible and in-session loss is zero.

### Phase 3: Interval schema + honest time attribution (the accuracy core, ~3 days)
- Schema v3 migration (3.2), keeping the `timestamp` column name and `onDowngrade` no-op (EC-60). Interval rows with `start_timestamp`/`source`/`flags`.
- Per-event attribution with the forward/backward clock gates (4.3), segment building, midnight splitting, `gap`/`boot_gap` marking (EC-7/EC-8/EC-24/EC-25/EC-27/EC-32).
- Wake-up sensor + 5 min `maxReportLatency` (EC-8, battery). This is the phase that makes hourly and daily breakdowns match reality instead of clumping at flush time.

### Phase 4: Lifecycle hardening (~2 days)
- `startForeground` unconditionally per start command; `ensureRunning()` idempotent ladder; derived `isRunning` (EC-15/EC-16/EC-20).
- Resurrection ladder: AlarmManager retry + persisted JobScheduler watchdog + resume notification; `onTaskRemoved`; `tracking_enabled` flag honored by boot/update (EC-18/EC-23/EC-28/EC-41/EC-42).
- Permission checks, silence watchdog on `lastProgressElapsed`, corruption handler that renames rather than deletes (EC-17/EC-21/EC-38).

### Phase 5: API surface + queries (~1 day)
- Serviceless reads, `Number.toLong()` arg coercion, `Dispatchers.IO` for all queries, `seq` paging (EC-30/EC-44/EC-45).
- `getTrackingStatus()` with `lastProgressAgeMs`; additive timeline fields; corrected `TimeStampUtils` docs (EC-47).

### Test strategy (applies to every phase)
- **Unit (JVM, no device):** feed a synthetic `List<SensorEventLike>` (value, event.timestamp, accuracy) plus a mockable clock/boot-count/elapsedRealtime into the manager. Assert the DB row set. This is where every EC scenario becomes a regression test: garbage value -> no rows; 500k in 5 s -> quarantine, zero rows; 42k over 10 real days -> one gap row spanning the window; boot-count change -> re-anchor, boot_gap credit. The current code is nearly untestable because it reads the clock and prefs directly; the anchor/segment split makes it a pure function of (events, clock, anchor).
- **Instrumented (device/emulator):** migration v1->v3 and v2->v3 with seeded old DBs; DPS move; process-death mid-flush via `Runtime.getRuntime().halt()` in a test hook, then assert re-derivation on restart; corruption handler via a deliberately truncated DB file.
- **Field validation:** ship phase 0 logging first; after each phase, export `health_log` from real POCO/Vivo/Realme devices and confirm the targeted generator count drops to zero. The 100000-step CHECK constraint (3.1) turns any residual logic bug into a loud crash in QA rather than a silent 2-billion-step row in production.

### Rollout safety
- The migration never wipes on failure (rolls back, retries next open), and `onDowngrade` is a no-op, so a bad build can be rolled back without data loss (EC-22/EC-60).
- Deterministic UUIDs (3.3) mean that if you ship server-side sync, a re-derived row after any restore dedupes against what was already uploaded (EC-10), so phasing does not create duplicate cloud data mid-migration.

Files touched: `/Users/dajirajinfotech/Projects/Packages/steps_count/android/src/main/kotlin/com/dajiraj/steps_count/StepCountManager.kt`, `StepCountDatabase.kt`, `BackgroundServiceManager.kt`, `BootServiceManager.kt`, `StepsCountPlugin.kt`, `TimeStampUtils.kt`, plus `/Users/dajirajinfotech/Projects/Packages/steps_count/android/src/main/AndroidManifest.xml` (currently empty; gains service/receiver/permissions/uses-feature/backup rules per 7.1) and new `res/xml/steps_count_backup_rules.xml` / `steps_count_extraction_rules.xml`.