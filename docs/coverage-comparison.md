# steps_count: before / after edge-case coverage

A case-by-case comparison of the original Android code against the reworked engine, across every edge case surfaced in the review. The original code did not lack handling so much as handle things in ways that *caused* the phantom spikes; the new engine addresses each mechanism at its root. Sensor-only (no Health Connect). See [robust_step_counting_spec.md](robust_step_counting_spec.md) for the full design, and `coverage-comparison.html` for an interactive, filterable version.

> Coverage claims reflect the shipped implementation (unit tests + correctness-by-construction), not device testing, which is still pending.

## Summary

- **60 edge cases audited**: 8 critical, 18 high, 21 medium, 13 low.
- **Areas**: Sensor/HW 12, Persistence 8, Code bug 14, Time/clock 12, Lifecycle 14.
- **Status**: 51 fixed structurally, 4 partial (narrow residual), 5 documented limits.
- **Tests**: 24 automated (was 0 real). The four independent phantom-spike generators are all in the fixed band.

| Coverage | Original code | Reworked engine |
|---|---|---|
| All 60 cases | unhandled or actively harmful | 51 fixed, 4 partial, 5 documented limits |
| Critical + high (26) | all present | all addressed |
| Automated tests | 0 real (1 broken template) | 24 |


## Critical severity (8)

### EC-1 &middot; Sensor / HW &middot; Fixed &middot; P1
**Scenario.** OEM sensor hub emits one garbage event: uint32 0xFFFFFFFF cast to float (4.2949673E9), Float.MAX_VALUE, or +Infinity after a hub crash/firmware glitch (documented on Xiaomi/POCO/Vivo/Realme sensor hubs). Delta = garbage - baseline saturates via Double.roundToInt() to Int.MAX_VALUE.

**Before.** StepCountManager.kt:133 computes the saturated delta; :143-150 caps it to MAX_REASONABLE_DELTA=500,000 and ADDS the cap as real steps, and re-baselines lastSensorValue to the garbage value (Infinity is even persisted to prefs via putFloat at :110-114). The next sane event yields a huge negative delta, treated as 'sensor reset' at :171-178, silently restoring the baseline. Net result: +500,000 phantom steps in pendingSteps, which flushPendingSteps at :209-217 slices into consecutive MAX_STEPS_PER_ROW=50,000 rows, one per 60s flush cycle, each stamped 'now' at :221-222. Exactly matches the field signature of 40K-50K single entries.

**Symptom.** Spike: up to ten consecutive 50,000-step rows (500K phantom total) from a single garbage event. Correct handling for an implausible delta is discard-and-rebaseline, never add-the-cap.

**After.** `isFinite && 0 <= v <= 1e9` gate pre-arithmetic; garbage discarded with anchor AND baseline untouched; implausible deltas quarantine against a frozen anchor; cap-and-add deleted.

### EC-2 &middot; Persistence &middot; Fixed &middot; P4
**Scenario.** Android Auto Backup or OEM migration tools (Mi Mover, OPPO/Realme Clone Phone, vivo EasyShare) restore steps_count_prefs on reinstall or onto a NEW device. No allowBackup=false, fullBackupContent, or dataExtractionRules exist anywhere (plugin manifest android/src/main/AndroidManifest.xml is empty; example manifest sets nothing), so backup defaults ON and includes SharedPreferences AND the SQLite DB. Restored prefs carry a stale lastSensorValue, is_initialized=true, and possibly stale pending_steps. First sensor event computes delta = (this device's cumulative-since-boot) - (old/stale baseline): an arbitrary number.

**Before.** loadState (StepCountManager.kt:88-98) trusts restored prefs blindly; onSensorChanged skips re-baseline because isInitialized is true (:124-130) and accepts any positive delta up to 500,000 (:143-155); over 500K, the cap is added anyway (EC-1 path); flush slices into 50,000-step rows (:211-217). No restore detection, no boot-session check (no boot-count or elapsedRealtime anchor), no backup exclusion. Additionally the backup pass copies prefs and DB at different instants while the service is live, and can capture the DB main file without its -wal companion, so restored pending_steps disagrees with the restored DB.

**Symptom.** Phantom spike of up to 500,000 steps delivered as successive 50,000-step rows stamped 'now'. Cross-device restore books pure fabrication; same-device reinstall lumps the whole uninstalled period into one moment. Strongest candidate for the unexplained 40-50K field reports.

**After.** DPS storage (never backed up) + manifest backup rules + device_id/install_id/boot regression checks -> anchor voided, first event credits 0.

### EC-3 &middot; Sensor / HW &middot; Fixed &middot; P1
**Scenario.** Any delta between 50,001 and 500,000 arrives (garbage value below the saturation range, backup-restore baseline mismatch, hub firmware jump). Human cadence tops out near 4 steps/sec, so even 24 hours between events bounds a real delta below ~350K theoretical and ~60K realistic; 100K+ in one callback is never real walking.

**Before.** StepCountManager.kt:45 sets MAX_REASONABLE_DELTA=500,000 and :143-155 accepts anything at or below it whole. The comment at :41-43 justifying '8h walk+cycle can exceed 100k' is physically wrong (cycling barely triggers the step counter; 100K steps needs ~7 sustained hours at world-record cadence). No rate-based plausibility check exists, and none is possible today because event.timestamp is ignored so elapsed time since the previous event is unknown.

**Symptom.** Spike: 50K-500K phantom deltas are recorded as-is and sliced into multiple 50K rows; the plausibility gate is roughly 1000x too loose.

**After.** `plausibleMax` burst+sustained envelope on monotonic per-event dt; implausible = quarantine/discard, never record.

### EC-4 &middot; Code bug &middot; Fixed &middot; P2
**Scenario.** Any real DB write failure: disk full, database corrupted or deleted by the corruption handler, table missing because onCreate's error was swallowed, or DB closed by the cleanup race. db.insert returns -1 or throws inside insertStepCount.

**Before.** insertStepCount (StepCountDatabase.kt:128-148) swallows every exception and returns null; flushPendingSteps (StepCountManager.kt:222) ignores the return value entirely. The flush proceeds as if it succeeded: pendingSteps stays zeroed (getAndSet(0) at :206), prefs pending is overwritten (:225), and the notification is refreshed (:230). The restore-on-failure catch at :231-236 is unreachable for every DB failure mode because insertStepCount never throws. onCreate of the DB also swallows failures (StepCountDatabase.kt:46-54), making every later insert fail forever.

**Symptom.** Every failed batch (up to 50,000 steps) is silently and permanently lost while the code logs success; with a persistently broken DB the plugin discards 100 percent of steps forever with no error surfaced to Dart.

**After.** Insert failures throw; transaction rolls back rows+anchor together; steps stay recoverable in the counter; reopen backoff; error surfaced. Failure means retry, never consume-without-store.

### EC-5 &middot; Persistence &middot; Fixed &middot; P2
**Scenario.** Doze/OEM batching delivers one onSensorChanged with a large delta (e.g. 20,000). The baseline advances and saveBaseline() queues an async apply() (disk write deferred by QueuedWork). The threshold flush inserts the 20,000 into SQLite, whose commit is immediately durable. A POCO/Vivo battery killer then force-kills the process before QueuedWork writes shared_prefs to disk (abrupt kills skip QueuedWork.waitToFinish). On restart, prefs revert to the pre-batch on-disk snapshot with the stale last_sensor_value; the next sensor callback recomputes the same 20,000 delta and inserts it again.

**Before.** StepCountManager.kt:109-115 persists the baseline with async apply(); :222 commits the same steps to SQLite synchronously; nothing couples DB commit durability to prefs durability, so at any abrupt kill SQLite is ahead of the on-disk baseline and loadState (:88-98) trusts the stale value.

**Symptom.** Spike / double-count: an exact duplicate of every batch flushed after the last durable prefs snapshot (1K-50K phantom entries matching the field reports).

**After.** Prefs removed from counting; baseline (anchor) advances only atomically with the rows it credits.

### EC-6 &middot; Persistence &middot; Fixed &middot; P2
**Scenario.** A large accepted delta (e.g. 100K-300K from backup restore, missed reboot, or glitch under the 500K cap) is drained 50,000 per flush via the per-row cap. After chunk N's SQLite commit, the prefs write of the new remainder is an apply() that never reaches disk because the process is killed or power is lost. Restart restores pending_steps = the remainder BEFORE chunk N, which still includes the 50,000 just inserted; the next flush inserts them a second time. Same mechanism at small scale: any lost post-flush apply() restores a stale pending value already in the DB.

**Before.** StepCountManager.kt:211-217 defers the remainder back into pendingSteps in memory; :225 persists pending only after the insert and only with apply(), so the on-disk value overstates by exactly one 50,000 chunk for the whole drain window. loadState (:92) restores the stale value; the deferral mechanism keeps large values parked across multiple flush cycles, widening the window.

**Symptom.** User-visible phantom row of up to exactly 50,000 steps, i.e. the max-size single-entry spikes users report; a 100K batch becomes 150K. Compounds precisely when phantom/backlog deltas already occurred.

**After.** No prefs pending, no 50k deferral loop; large catch-ups are single midnight-split interval rows; nothing stale to restore.

### EC-7 &middot; Time / clock &middot; Fixed &middot; P3
**Scenario.** OEM kills the service (autostart blocked) but the phone stays powered on for hours/days/weeks. TYPE_STEP_COUNTER hardware keeps counting. User opens the app, service restarts; loadState restores the pre-downtime baseline with isInitialized=true, so the first sensor event carries the entire downtime total (e.g. 42,000 real steps over 10 days) as one delta.

**Before.** Delta computed against the persisted baseline at StepCountManager.kt:133, buffered, then flushed with wall-clock time AT FLUSH via TimeStampUtils.getCurrentUtcTimestamp() at :221-222. Nothing back-dates or spreads the catch-up; event.timestamp is never read. Deltas over 50K are split by the MAX_STEPS_PER_ROW deferral (:211-217) into consecutive 50K rows minutes apart, all on the restart day.

**Symptom.** Spike: the exact reported field symptom. Days of steps compressed into one (or a staircase of) huge entries on the restart day; every other downtime day reads 0. Daily totals wrong on N+1 days at once. On these OEMs service death is routine, so users see this repeatedly.

**After.** Same-boot downtime delta booked as `source='gap'` over `[anchor wall, now]`, gated, midnight-split; never stamped "now".

### EC-8 &middot; Sensor / HW &middot; Fixed &middot; P3
**Scenario.** User walks with screen off; SoC suspends (the foreground service holds no wakelock). getDefaultSensor(TYPE_STEP_COUNTER) returned the NON-wake-up variant, so buffered step events sit in the sensor hub FIFO and are delivered as a burst only when the SoC wakes (screen on / unlock). On POCO/Vivo/OPPO/Realme aggressive-suspend builds this silence routinely lasts hours. Variant: the walk ends at 23:40 but the phone wakes at 00:15.

**Before.** BackgroundServiceManager.kt:98 requests the default (non-wake-up) sensor with SENSOR_DELAY_NORMAL and no maxReportLatency (:136-138). All burst events are merged into pendingSteps and flushed with wall-clock time at flush (StepCountManager.kt:221-222), so an entire afternoon walk lands as one 1K-10K row stamped at unlock time. The fix path exists: getDefaultSensor(TYPE_STEP_COUNTER, true) for the wake-up variant, plus per-event timestamps to backdate.

**Symptom.** Spike + misattribution: this is the everyday 1K-2K (up to 10K) single-entry spike generator on exactly the reported devices; steps are real but time-collapsed to one instant, and sessions ending before midnight move to the wrong calendar day.

**After.** Wake-up sensor + 5 min maxReportLatency + per-event timestamps reconstruct screen-off walks at true times; pre-midnight walks stay on their day.


## High severity (18)

### EC-9 &middot; Time / clock &middot; Fixed &middot; P3
**Scenario.** A batched FIFO delivery after suspend contains MANY events, each carrying event.timestamp (elapsedRealtimeNanos of the last step in that event), which would let steps be distributed across the silent period and enable rate-based sanity checks.

**Before.** BackgroundServiceManager.kt:225-235 forwards only event.values[0]; the timestamp is dropped before StepCountManager ever sees it, so even when the hub hands over per-interval attribution the plugin discards it and books everything at flush wall-clock (StepCountManager.kt:221). Design constraints for the fix: convert via currentTimeMillis() - elapsedRealtime() + event.timestamp/1e6 recomputed PER EVENT (a cached anchor recreates clock-jump bugs after adjustment or reboot), and clamp results to (last written row timestamp, now] because some Xiaomi/vendor HALs emit bogus event.timestamps (future-dated or epoch-based).

**Symptom.** Misattribution: hourly/daily breakdowns are wrong whenever delivery is deferred; discarding the timestamp also blocks every rate-based plausibility check (see EC-3), making this the enabling defect for most spike fixes.

**After.** Per-event `wallMs = curTimeMillis - elapsedRealtime + event.ts/1e6`, recomputed each event, clamped to `(watermark, now+2s]`.

### EC-10 &middot; Persistence &middot; Fixed &middot; P2
**Scenario.** Auto Backup double-count with an upstream sync. Backup snapshot taken at time T. User walks after T; rows are inserted and synced to a server via getTimelineAfter (dedup by uuid). User reinstalls / migrates; local rows after T are gone but the restored baseline is from T. The first sensor event re-derives all steps since T as a brand-new row.

**Before.** insertStepCount always generates a new random UUID (StepCountDatabase.kt:123-127), so the re-derived catch-up row cannot be deduplicated against rows already synced before uninstall. Additionally KEY_PENDING_STEPS is restored from backup (StepCountManager.kt:92) and re-flushed even though those steps were flushed on-device after the snapshot.

**Symptom.** Server-side double count: steps recorded and synced between backup time and uninstall are counted twice; the restored pending buffer adds a further duplicate row.

**After.** Backup excluded; migration preserves v2 uuids; deterministic uuids from (device, boot, counter range) make any re-derivation dedupe server-side; restore voids the anchor.

### EC-11 &middot; Sensor / HW &middot; Fixed &middot; P1
**Scenario.** Reboot with delayed service restart (OEM autostart denial, Xiaomi/Vivo scheduled power off/on). TYPE_STEP_COUNTER resets to 0 and climbs while the service is down. Case A: steps-since-boot < saved baseline, delta negative. Case B: previous baseline was small, steps-since-boot exceed it, delta positive.

**Before.** Reboot detection is solely 'delta < 0' (StepCountManager.kt:171-178). No boot-session marker is persisted (no boot id, no SystemClock.elapsedRealtime comparison, event.timestamp unused). Case A silently re-baselines and DISCARDS sensorValue, which IS the count of real steps walked since boot: every reboot swallows all steps between boot and the first serviced event (easily thousands for a morning commute). Case B credits only (stepsSinceBoot - oldBaseline), undercounting by the old baseline amount, and books the remainder as one lump at flush wall-clock time (:221). Refinement of the prior review: a missed reboot alone can never OVERCOUNT; phantom positives require backup restore (EC-2) or garbage values (EC-1).

**Symptom.** Silent permanent loss of all steps walked between boot and first event (possibly a multi-day gap), plus a misattributed multi-thousand lump at app-open time; erodes trust in the opposite direction from spikes.

**After.** BOOT_COUNT session marker; Case A credits `min(v, gate)` as `boot_gap` from boot time; Case B gates by wall gap; no silent discard, no lump-at-now.

### EC-12 &middot; Persistence &middot; Fixed &middot; P2
**Scenario.** Abrupt process death (MIUI swipe-kill, Vivo/OPPO battery optimizer SIGKILL, LMK, crash, permission-revocation kill, app update) at any moment after onSensorChanged() but before the next flush completes. Worst case: an OEM-batched event delivers 10,000+ steps in one callback and the kill lands in the seconds before the triggered flush coroutine writes the row.

**Before.** saveBaseline() advances and persists last_sensor_value immediately on every event, BEFORE the steps reach SQLite (StepCountManager.kt:149-153, apply at :109-115), while KEY_PENDING_STEPS is only persisted after a successful DB flush (:225). On restart loadState (:92) restores the stale pending value from the last flush; everything accumulated since is unrecoverable because the durable baseline has already moved past it and the hardware never re-delivers.

**Symptom.** Permanent silent loss of the entire unflushed buffer on every abrupt kill: normally up to ~49 steps / 60s, but up to an entire multi-hour OEM batch (tens of thousands of steps). On phones that kill the app daily this is a steady visible undercount.

**After.** Unflushed steps derived from `counter - anchor`; process death loses only memory, re-derived at next event; loss only at boot-session end, bounded Section 5.

### EC-13 &middot; Code bug &middot; Fixed &middot; P2
**Scenario.** Concurrent flushes with no mutex: every sensor event with pending >= 50 launches a new flush coroutine on Dispatchers.IO on top of the periodic one. Flush A finishes its insert, reads pendingSteps.get() = 20,000 (a batch that arrived during A's insert), and is preempted between the read and the apply(); flush B does getAndSet(0), inserts the 20,000, writes prefs pending = 0; A resumes and writes prefs pending = 20,000. Memory pending is 0 and the DB is complete, but on-disk prefs claim 20,000.

**Before.** StepCountManager.kt:168-170 launches a flush per qualifying event and :189-196 runs the periodic loop with no mutual exclusion; :225 writes a racy pendingSteps.get() snapshot; :206-207 returns early on an empty buffer without ever correcting KEY_PENDING_STEPS, including cleanup's final flush, so even a CLEAN service stop preserves the phantom. The next start restores 20,000 and flushes a phantom row.

**Symptom.** Durable phantom entry equal to a batch size that survives clean restarts; no abnormal kill needed.

**After.** Single-flight flush under `stateMutex`; no prefs pending to race on.

### EC-14 &middot; Code bug &middot; Fixed &middot; P2
**Scenario.** flushPendingSteps inserts a batch successfully, then onFlushSuccess() -> updateNotification() throws (NotificationManager.notify or packageManager.getLaunchIntentForPackage can throw RuntimeException/DeadSystemException under OEM memory-pressure kill storms, exactly the devices in question).

**Before.** onFlushSuccess() is called INSIDE the try block at StepCountManager.kt:230, after the DB insert (:222) and prefs write (:225). The catch at :231-236 assumes the DB write failed and re-adds toInsert to pendingSteps, and additionally re-adds the deferred remainder (:234) which was ALREADY added back at :213 before the try. For a 100,000 pending buffer: 50,000 is in the DB, and the buffer becomes 150,000, later flushed as three more 50,000 rows. This is the live path that turns the supposedly dead double-add code into real double counting.

**Symptom.** Steps already persisted get flushed again: up to 50,000 duplicated steps plus a twice-counted remainder per occurrence, appearing as extra 50K rows.

**After.** `onFlushSuccess()` outside the accounting try; commit is the success point; no restore-into-buffer path exists.

### EC-15 &middot; Lifecycle &middot; Fixed &middot; P4
**Scenario.** StepCountManager construction throws once in Service.onCreate: credential-encrypted SharedPreferences accessed pre-unlock (the receiver is directBootAware, inviting exactly that start path; the Service is not), corrupt prefs XML, or disk full.

**Before.** initializeStepManager (BackgroundServiceManager.kt:103-114) catches the exception and leaves the companion stepCountManager null, but onStartCommand proceeds: startService() sets isRunning=true, registers the sensor, and starts the foreground notification showing 'Today's Steps: 0'. onSensorChanged (:225-235) drops every event because manager==null. There is NO re-initialization attempt for the life of the process, and every future start attempt (plugin or receiver, including BootServiceManager.checkAndStartService) no-ops on 'if (isRunning) return' (:117), so isRunning=true actively blocks all recovery.

**Symptom.** Zombie foreground service: notification claims tracking is active while zero steps are recorded for the entire boot session; the stale baseline then turns the eventual real restart into loss or a giant catch-up spike.

**After.** `ensureRunning()` retried per start command/tick; failure -> degraded status, not zombie; DPS removes the pre-unlock CE crash cause.

### EC-16 &middot; Lifecycle &middot; Fixed &middot; P4
**Scenario.** Stop/start race wedges the service: Dart calls stopBackgroundService then startBackgroundService in quick succession (or the boot receiver retry races a user stop). onStartCommand(FORCE_STOP) runs stopService(): doCleanup() nulls stepCountManager, sets cleanupDone=true, and calls stopSelf(). The already-queued START_SERVICE command is delivered to the SAME instance before destruction: startService() sets isRunning=true, registers the sensor, calls startForeground, but stepCountManager is only ever created in onCreate so it stays null.

**Before.** BackgroundServiceManager.kt:103-114 creates the manager only from onCreate (:51-58); :116-125 restarts without recreating it; :227-233 silently drops every sensor event on the null-manager check. When onDestroy finally runs, doCleanup() early-returns at the cleanupDone guard (:208), so isRunning is never reset and the listener is never unregistered. isServiceRunning() keeps returning true, so StepsCountPlugin.startBackgroundService short-circuits with success (StepsCountPlugin.kt:73-76).

**Symptom.** Notification and API both claim tracking is active while zero steps are recorded, possibly for days, until the process dies; the eventual real restart produces a giant catch-up lump.

**After.** Idempotent re-init on every start; `isRunning` derived, reset unconditionally in `onDestroy`.

### EC-17 &middot; Lifecycle &middot; Fixed &middot; P4
**Scenario.** ACTIVITY_RECOGNITION is auto-reset (Android 11+ unused-app reset, Android 12+ hibernation) or revoked mid-run. On API 29+ registerListener for TYPE_STEP_COUNTER still returns true without the permission; sensorservice simply never delivers events. Mid-run revocation additionally kills the process immediately at an arbitrary point (buffer loss per EC-12). Weeks later the user opens the app and re-grants.

**Before.** registerSensor (BackgroundServiceManager.kt:134-149) only checks the boolean return, which stays true, so nothing is logged or surfaced; the service looks healthy while counting zero. No permission check and no event-silence watchdog exist anywhere. After re-grant plus process restart, the first event's delta spans the entire silent gap and is flushed as one row at 'now'.

**Symptom.** Long silent loss window followed by a multi-thousand catch-up spike: user sees weeks of 0 then one absurd entry.

**After.** Explicit permission check + status API + silence watchdog; re-grant catch-up = gated, marked `gap` row.

### EC-18 &middot; Lifecycle &middot; Fixed &middot; P4
**Scenario.** Android 14+ (targetSdk 34): startForeground with FOREGROUND_SERVICE_TYPE_HEALTH throws SecurityException when ACTIVITY_RECOGNITION is not granted (user denied it, or it was auto-reset), at every boot-receiver restart and every plugin-initiated start.

**Before.** startForegroundService's catch (BackgroundServiceManager.kt:156-171) logs and calls stopSelf(); BootServiceManager retries once 2-3s later and fails identically. Nothing is surfaced to Dart: StepsCountPlugin.startBackgroundService already returned result.success(true) fire-and-forget right after sending the intent (StepsCountPlugin.kt:83-90). Even on API 29-33 where the FGS starts, the sensor delivers nothing without the permission, and neither plugin nor service checks or reports this.

**Symptom.** For every user who denied the permission, tracking dies silently on every boot on Android 14/15 (a start-then-SecurityException-stop loop) while the app believes it started; long gaps followed by a catch-up spike on recovery.

**After.** SecurityException caught -> `fgs_denied` diag + resume notification + clean stop, `START_NOT_STICKY`; plugin surfaces `last_start_failure`.

### EC-19 &middot; Sensor / HW &middot; Fixed &middot; P4
**Scenario.** MIUI/HyperOS, ColorOS (OPPO/Realme), FuntouchOS/OriginOS (Vivo) power managers gate sensor event delivery or freeze the app process despite the foreground service when the app lacks OEM whitelisting (autostart, 'no battery restrictions'). Hours of silence, then a single catch-up event at user unlock or app open; some builds tear down the sensor connection entirely so events never resume without re-registration.

**Before.** Same collapse-at-now outcome as EC-8 via StepCountManager.kt:221-222, but caused by OEM policy rather than SoC suspend, so a wake-up sensor alone will not fix it. The code never re-registers the listener after the initial registerSensor (BackgroundServiceManager.kt:134-149) and tracks no last-event time, so a torn-down connection is indistinguishable from an idle user.

**Symptom.** Spike + misattribution on exactly the reported OEM set; or permanent silent loss when the connection is killed.

**After.** 6 h silence watchdog re-registers; eventual burst is per-event-attributed or marked `gap`, never a "now" lump.

### EC-20 &middot; Lifecycle &middot; Fixed &middot; P4
**Scenario.** startForegroundService is called a second time while the service is already running and startService() early-returns without calling startForeground again. Concrete triggers: (a) device with no lockscreen PIN: LOCKED_BOOT_COMPLETED starts the service, then BOOT_COMPLETED fires seconds later and BootServiceManager calls startForegroundService again unconditionally; (b) the 3s verification (BootServiceManager.kt:65-73) sees isRunning=false during a slow boot storm and retries while the first start is in flight; (c) the user opens the app right after boot and the plugin's isServiceRunning() check races the receiver's start.

**Before.** BackgroundServiceManager.startService() (BackgroundServiceManager.kt:116-125) begins with 'if (isRunning) return', so the second startForegroundService call is never answered with a Service.startForeground() call. Android arms a startForeground timeout per startForegroundService call; the unanswered one raises RemoteServiceException / ForegroundServiceDidNotStartInTimeException and kills the process.

**Symptom.** 'App keeps stopping' crash right after boot or app-open; the process kill also discards in-memory pending steps and can strand un-applied prefs writes: crash plus count corruption.

**After.** `startForeground()` unconditionally in `onCreate` and per `onStartCommand`, answering every `startForegroundService` promise.

### EC-21 &middot; Persistence &middot; Fixed &middot; P4
**Scenario.** A single SQLiteDatabaseCorruptException during any open or query (unclean power loss mid-commit on budget eMMC, torn WAL header). The helper was constructed without a custom DatabaseErrorHandler, so Android's DefaultDatabaseErrorHandler deletes step_count.db outright and a fresh empty DB is created on next open.

**Before.** StepCountDatabase.kt:18-19 passes no DatabaseErrorHandler to SQLiteOpenHelper; every read/write path catches exceptions and returns 0/empty/null, so the wipe is invisible to the app layer.

**Symptom.** Entire step history silently destroyed in one event; lifetime totals reset to zero with no error surfaced.

**After.** Custom DatabaseErrorHandler renames (never deletes) the corrupt file; recovery logged and surfaced.

### EC-22 &middot; Persistence &middot; Fixed &middot; P2
**Scenario.** DB migration or creation fails mid-way: disk-full or I/O error during migrateV1ToV2's single full-table INSERT...SELECT (no space check), a first-install onCreate failure, or the astronomically rare abs(random()) integer-overflow abort in the UUID SQL at StepCountDatabase.kt:95 (SQLite abs() raises on Long.MIN_VALUE). SQLiteOpenHelper runs onCreate/onUpgrade inside a transaction and then writes the new version; catching the exception inside the callback makes the helper commit the version anyway.

**Before.** onUpgrade's catch (StepCountDatabase.kt:56-68) executes DROP TABLE IF EXISTS steps + onCreate as 'last resort': by then v1 data was renamed to steps_old (:80), so all history is stranded invisibly in steps_old while a fresh empty table is committed as v2; steps_old is never cleaned up or recovered. Worse, onCreate (:46-54) also swallows failures, so a failed CREATE TABLE still commits DATABASE_VERSION=2 with NO steps table at all; onCreate never runs again and every subsequent insert fails silently forever via EC-4.

**Symptom.** One transient error at app-update/install time wipes the visible step history or bricks the DB permanently with total silent loss of all future steps: the worst possible trust event.

**After.** No catches in `onCreate`/`onUpgrade` (rollback + retry), free-space precheck, no DROP fallback, `steps_old` salvage.

### EC-23 &middot; Lifecycle &middot; Mitigated (documented limit) &middot; P4
**Scenario.** User force-stops the app (Settings, or an OEM 'deep clean' implemented as force stop). Process is killed with no onDestroy, and the app enters the stopped state.

**Before.** START_REDELIVER_INTENT (BackgroundServiceManager.kt:77) is cancelled by force stop, and stopped-state apps receive no manifest broadcasts, so BOOT_COMPLETED / MY_PACKAGE_REPLACED / USER_PRESENT (BootServiceManager.kt:16-30) are all withheld until the user explicitly relaunches the app. Nothing records or detects the outage.

**Symptom.** Unbounded tracking gap (even across reboots) that ends only on manual app launch, followed by one misattributed catch-up lump; buffered steps at kill time are also lost (EC-12).

**After.** Framework limit documented; onTaskRemoved job + persisted JobScheduler watchdog + resume notification + gap recovery bound and mark the outage.

### EC-24 &middot; Time / clock &middot; Fixed &middot; P3
**Scenario.** Wall clock steps BACKWARD while counting: user fixes a fast clock, NITZ correction, or carrier push. Budget dual-SIM phones (exactly the affected POCO/Vivo/Realme fleet) additionally flap the clock by tens of seconds to minutes many times per day when hopping between towers or SIMs. Rows already exist stamped 18:00-18:05; subsequent flushes stamp 16:01, 16:02. Variant: a correction at 00:10 back to 23:50 crosses midnight, orphaning the 00:0x rows beyond today's window.

**Before.** Flush stamps raw System.currentTimeMillis with no monotonicity guard against the last written row (StepCountManager.kt:221; no max-timestamp query exists in StepCountDatabase.kt). getTimelineDataAfter uses strict 'timestamp > ?' (StepCountDatabase.kt:263-271), so any consumer syncing with a lastSync watermark will NEVER receive the backdated rows. Timeline ORDER BY timestamp ASC interleaves rows out of true chronological order; midnight-crossing corrections make just-walked steps vanish from today's display then reappear pre-banked.

**Symptom.** Loss + misattribution: a steady trickle of rows silently never synced (backend undercount with no error signal), day-boundary flushes landing on the wrong side of midnight, and out-of-order timelines.

**After.** Strictly-increasing watermark: backward clocks compress at `watermark+1` (flagged), never backdate; legacy `> lastSync` cursor provably never starves; monotonic dt for gating; `seq` cursor immune entirely.

### EC-25 &middot; Time / clock &middot; Fixed &middot; P3
**Scenario.** Wall clock jumps FORWARD then is corrected: NITZ glitch, or the user manually sets the date days ahead (common for game time-skip cheats on exactly the POCO/Vivo demographic), walks, then sets it back. Sync variant: getTimelineAfter returns one future-stamped row, the app advances its lastSync watermark to that future timestamp (e.g. 12 days ahead), then the clock is corrected.

**Before.** Flushes during the wrong-forward period write rows stamped in the future (StepCountManager.kt:221); nothing detects or clamps timestamps > now on read or write. Future rows match no current-day query (TimeStampUtils.kt:31-32, StepCountDatabase.kt:299-317). After the watermark is poisoned, every row written post-correction has timestamp < the watermark, so getTimelineAfter returns empty until real time passes the phantom timestamp (StepCountManager.kt:377-402, StepCountDatabase.kt:258-271).

**Symptom.** Loss then spike: today's steps show 0 after correction, then reappear pre-banked when the future date arrives; one transient glitch silently halts step sync for days/weeks and all rows written in that window are skipped forever.

**After.** Forward wall jumps clamped to the anchor-projected monotonic time (`expectedWall + 15 min`), NOT to the poisoned wall clock, so a multi-day date-set forward no longer poisons the watermark or day attribution; the new offset is adopted only after it stays stable >= 1 h (transient set self-heals); `now + 2s` absolute backstop retained; migration clamps existing future v2 rows; `seq` cursor unaffected.

### EC-26 &middot; Lifecycle &middot; Fixed &middot; P4
**Scenario.** The unlock-time recovery path never fires on modern Android. ACTION_USER_UNLOCKED is broadcast with FLAG_RECEIVER_REGISTERED_ONLY (never delivered to manifest receivers), and ACTION_USER_PRESENT is an implicit broadcast not on the API 26+ exemption list, so manifest receivers never get it. The only working post-unlock trigger is BOOT_COMPLETED, which MIUI/ColorOS/FuntouchOS autostart managers routinely block for non-whitelisted apps.

**Before.** BootServiceManager.kt:22-24 handles both actions and the example manifest registers them (AndroidManifest.xml:59-60), but on API 26+ (virtually all field devices) checkAndStartService() is unreachable dead code. Related manifest trap: the second MY_PACKAGE_REPLACED intent-filter (manifest :65-68) pairs it with a data scheme so it can never match; only the first filter works, and 'cleaning up' the working duplicate would kill restart-after-update entirely.

**Symptom.** False confidence: the intended 'restart on every unlock' safety net does not exist, so mid-day OEM kills persist until the next boot/update/app launch, feeding the gap-plus-lump pattern on exactly the reported devices.

**After.** Dead manifest unlock filters removed; runtime `USER_UNLOCKED` receiver in the direct-boot service; broken data-scheme `MY_PACKAGE_REPLACED` filter deleted, working one kept.


## Medium severity (21)

### EC-27 &middot; Time / clock &middot; Fixed &middot; P3
**Scenario.** Boot before time sync: BOOT_COMPLETED starts the service on a device with a drained battery or weak RTC (common on Xiaomi/POCO); the wall clock reads 1970 or a stale last-known time until NITZ/NTP arrives minutes later. Restored pendingSteps and any early steps flush during that window.

**Before.** BootServiceManager starts the service immediately on boot actions (BootServiceManager.kt:16-24, 44-62); StepCountManager's init loads pending and starts the 60s flush loop right away (StepCountManager.kt:80-83), stamping rows with the current, possibly bogus wall clock (:221). No monotonic (elapsedRealtime) anchor and no sanity check rejects timestamps before the last written row.

**Symptom.** Loss + misattribution: rows dated 1970/stale never appear in today's queries, are permanently skipped by 'timestamp > lastSync' sync (strictly-greater cursor, StepCountDatabase.kt:266), and pollute whatever ancient day they land on.

**After.** Gap starts projected on elapsedRealtime; pre-NITZ stamps clamp to `watermark+1` flagged CLOCK_CLAMPED; nothing lands in 1970.

### EC-28 &middot; Lifecycle &middot; Fixed &middot; P4
**Scenario.** Boot receiver relies on Handler.postDelayed after onReceive returns. Once onReceive returns, a receiver-only process has no active component; if the initial startForegroundService failed (OEM ForegroundServiceStartNotAllowedException, autostart denied) there is no FGS either, so the process is prime for immediate death.

**Before.** The 3s verification/retry (BootServiceManager.kt:65-73) and the 2s exception retry (:81-85) are posted to the main looper with no goAsync(), no PendingResult, and no wakelock; if the process dies first they never execute. The verification also only reads the in-process static isRunning (BackgroundServiceManager.kt:34), which cannot see anything the process no longer knows.

**Symptom.** On exactly the OEM devices that block the first start, the only recovery mechanism silently evaporates; tracking stays down until manual launch, producing gap-plus-lump.

**After.** AlarmManager retry PendingIntent (survives process death) replaces `postDelayed`; cancelled by the service on success.

### EC-29 &middot; Lifecycle &middot; Fixed &middot; P4
**Scenario.** FBE device boots with a lock screen: the directBootAware receiver gets LOCKED_BOOT_COMPLETED before unlock, but the Service is NOT directBootAware (example manifest :45-49 vs :52-55) and StepCountManager opens credential-encrypted SharedPreferences/SQLite which are unavailable before user unlock, so the pre-unlock start fails.

**Before.** The startForegroundService failure lands in the fragile postDelayed retry (EC-28); USER_UNLOCKED/USER_PRESENT delivery is dead (EC-26), so recovery waits for BOOT_COMPLETED after first unlock or app open. Steps walked between boot and the first serviced event are then consumed by the negative-delta re-baseline (StepCountManager.kt:171-178) and lost (EC-11). The service's non-directBootAware status is currently the only accidental guard against EC-15's CE-storage crash; making the receiver more aggressive without fixing storage access would trigger it.

**Symptom.** Recurring loss every reboot (boot-to-unlock steps) plus extended service downtime feeding one giant misdated catch-up entry.

**After.** Service directBootAware + DB/prefs in DPS: pre-unlock counting fully works from `LOCKED_BOOT_COMPLETED`; no CE crash.

### EC-30 &middot; Code bug &middot; Fixed &middot; P5
**Scenario.** All read APIs return 0/empty whenever the service is not running, even though step_count.db sits intact on disk. Trigger: any period between a kill/reboot and the next service start; a Dart-side widget, background isolate, or sync job querying counts in that window. Transient DB errors inside a read are also swallowed and rendered as 0 (StepCountManager.kt:275-278, :306-309).

**Before.** getTodaysCount, getStepCount, getTimeline, getTimelineAfter all return 0 or [] when BackgroundServiceManager.stepCountManager is null (StepsCountPlugin.kt:141-145, 156-160, 172-176, 186-190) instead of opening the existing database read-only.

**Symptom.** User opens the app after a kill and sees today's steps as 0: perceived data loss. Sync layers can persist/upload the zeros, overwriting good server data, then show a jump when the service restarts.

**After.** Never-closed singleton DPS DB serves all reads when the service is down; errors surface as errors, not zeros.

### EC-31 &middot; Code bug &middot; Mostly fixed (narrow residual) &middot; P5
**Scenario.** App draws an hourly chart querying getStepCount per hour bucket, queries a narrow range like the last 5 minutes, or snapshots the day total at 23:59 while the pending buffer holds hundreds/thousands of steps that will flush after midnight.

**Before.** getStepCount (StepCountManager.kt:268-274) adds the ENTIRE pending buffer (up to 1,000,000) to any range whose bounds merely straddle 'now', regardless of range width, so a 1-second range can return the whole buffer. getTodaysCount (:287-310) always adds the full buffer to today. The same steps are later inserted with a post-midnight flush timestamp (:221), so snapshot consumers count them in day N (live query) and again in day N+1 (DB row). A backward clock change makes a re-queried historical day 'include now' again and absorb today's buffer.

**Symptom.** Sub-day queries inflated by unrelated buffered steps, per-hour charts show phantom spikes in the current bucket, and day totals no longer reconcile with the timeline sum (double-count across midnight).

**After.** Live contribution = timestamped segments overlapping the queried window only; narrow ranges get only their share.

### EC-32 &middot; Time / clock &middot; Fixed &middot; P3
**Scenario.** Deferred or stale pending steps replayed under a new clock: a deferred burst crosses midnight (walk gated in the evening, burst flushes at 00:30; 50K-deferral remainder rows land 60s apart straddling the boundary), or pendingSteps persisted to prefs (including up to ~950K deferred remainder from a capped glitch delta) survives process death/reboot and is flushed days later.

**Before.** loadState restores the prefs value into pendingSteps (StepCountManager.kt:92); the 60s periodic flush writes it with a fresh 'now' stamp (:190-195, :221); the deferral loop (:211-217) drains a large remainder as repeated 50K rows, one per flush tick, each stamped at its own flush time. getTodaysCount buckets strictly by row timestamp (:287-310), so yesterday's steps are booked to today.

**Symptom.** Misattribution: yesterday ends low and today starts with a phantom pre-dawn block; steps (or glitch counts) accumulated days earlier appear as a staircase of large entries at times the user demonstrably was not walking.

**After.** No persisted pending buffer; rows midnight-split at write; gap rows carry their true interval, never flush time.

### EC-33 &middot; Time / clock &middot; Fixed &middot; P3
**Scenario.** Flush cadence vs CPU suspend: device in Doze or deep sleep between maintenance windows while the user walks screen-off. The '60 second' periodic flush uses kotlinx delay, which parks on uptime-based clocks that STOP during SoC suspend, unlike wall time and unlike SystemClock.elapsedRealtime.

**Before.** startPeriodicFlush uses delay(FLUSH_INTERVAL_MS) on Dispatchers.IO (StepCountManager.kt:66, 189-196); there is no AlarmManager/elapsedRealtime-based schedule. Between suspends the timer barely advances, so a flush can occur hours of wall time after accumulation, and the stamp is taken at that late flush (:221).

**Symptom.** Misattribution + loss window: stamps drift hours from when steps happened (routinely crossing local midnight), and unflushed steps sit in RAM far longer than 60s, enlarging the loss on process death.

**After.** Flush deadline on elapsedRealtime, re-checked at every event delivery; stamps come from event timestamps, so delay drift is correctness-neutral anyway.

### EC-34 &middot; Time / clock &middot; Fixed &middot; P3
**Scenario.** Midnight race inside getTodaysCount: the periodic flush or a poll fires within a few ms of local midnight. getTodaysTimestamp(true) runs at 23:59:59.999 (start = yesterday 00:00) and getTodaysTimestamp(false) runs at 00:00:00.001 (end = today 23:59:59.999).

**Before.** Start and end are computed by two independent LocalDate.now() calls (StepCountManager.kt:289-290 calling TimeStampUtils.kt:25-34), so the window can span 48 hours. The inflated result is pushed into the persistent foreground notification via onFlushSuccess -> updateNotification (BackgroundServiceManager.kt:108, 217-223) and sticks until the next non-empty flush (flush early-returns when pending is 0, StepCountManager.kt:207).

**Symptom.** Spike: the notification (and any app poll at that instant) shows yesterday + today combined, roughly doubling the count exactly at midnight, potentially displayed all night.

**After.** Day window from one `ZonedDateTime.now` snapshot, half-open.

### EC-35 &middot; Time / clock &middot; Fixed &middot; P3
**Scenario.** Stale notification across midnight: user walks 8,000 steps, sleeps at 23:30; no steps overnight means every periodic flush early-returns. User checks the phone at 06:30.

**Before.** The notification text is only rebuilt on service start and on successful flush (BackgroundServiceManager.kt:108, 116-124, 217-223); flushPendingSteps returns before onFlushSuccess when the buffer is empty (StepCountManager.kt:206-207). There is no midnight-triggered refresh, so the notification still says 'Today's Steps: 8,000' after the day rolled over while the app UI says 0.

**Symptom.** Perceived spike: a daily-recurring phantom count in the persistent notification every morning; a highly plausible source of user spike reports.

**After.** Notification refreshed on flush and on SCREEN_ON; day window recomputed at display time.

### EC-36 &middot; Time / clock &middot; Mitigated (documented limit) &middot; documented
**Scenario.** Timezone change / travel: user flies Mumbai to Tokyo (+3:30) or the carrier pushes a zone change. Rows are absolute epoch millis; 'today' is recomputed in the new zone at query time.

**Before.** getTodaysTimestamp uses ZoneId.systemDefault() at call time (TimeStampUtils.kt:25-34); stored rows keep their flush-time epoch stamps (StepCountManager.kt:221). After the zone change, yesterday-evening steps fall inside the new 'today' window (eastward) or this morning's steps fall out of it (westward); all historical per-day totals silently recompute under the new zone.

**Symptom.** Misattribution: day totals visibly jump or drop mid-day after travel, and history disagrees with any backend that bucketed days under the old zone.

**After.** Epoch-pure rows + proportional overlap re-bucket honestly under a new zone; `tz_offset_min` recorded per row for a future written-zone API; `health_log('timezone_changed')`. Documented.

### EC-37 &middot; Sensor / HW &middot; Mitigated (documented limit) &middot; documented
**Scenario.** User rides a motorbike/auto-rickshaw or a bumpy bus; cheap hub step algorithms (frequent complaint on POCO/Realme/entry Vivo) count vibration as steps at the HARDWARE level, generating 500-3,000 false steps per hour of driving, delivered as ordinary small deltas.

**Before.** Indistinguishable from real walking at StepCountManager.kt:133-155: the plugin faithfully records what the hub says. No cadence/rate heuristic exists (and cannot until event.timestamp is used, EC-9). This explains a subset of the 1K-2K complaints that no baseline/reset fix will remove; only a per-elapsed-time rate cap can dampen it.

**Symptom.** Recurring 1-2K daily overcounts on affected hardware; sets a ceiling on achievable accuracy for a sensor-only plugin, so it should be documented as expected behavior.

**After.** Hardware floor documented; rate gate clips only >5/s bursts; per-event cadence in health_log enables future heuristics.

### EC-38 &middot; Sensor / HW &middot; Mitigated (documented limit) &middot; P4
**Scenario.** Sensor goes permanently silent mid-session: sensor HAL/hub crash where the framework's listener re-attach fails on OEM builds, or the documented MIUI 'step counter frozen until reboot' failure where the same value is reported indefinitely.

**Before.** No watchdog exists: neither BackgroundServiceManager nor StepCountManager records the time of the last sensor event, so days of silence raise nothing. Frozen-same-value events produce delta 0 and fall through both branches at StepCountManager.kt:135/171 forever. Steps are uncounted until reboot; the reboot then hits the negative-delta re-baseline (:171-178), making the gap permanently unrecoverable.

**Symptom.** Loss: multi-day zero counts with a healthy-looking notification; users read it as the app being broken.

**After.** Watchdog driven off `lastProgressElapsed` (accepted positive deltas only); zero-delta heartbeats from a frozen-but-delivering hub do NOT re-arm it, so 6 h without real progress trips unregister/re-register and logs `sensor_frozen_suspect` with the stuck value; `getTrackingStatus` exposes `lastProgressAgeMs`. Truly-silent connections covered as before. Steps lost inside a hub-freeze window (or while powered off) remain physically unrecoverable: documented limit, not a fixable gap.

### EC-39 &middot; Code bug &middot; Fixed &middot; -
**Scenario.** runBlocking cleanup on the main thread during stop/shutdown: stopService() and onDestroy() (BackgroundServiceManager.kt:127-132, 241-247) call StepCountManager.cleanup() while the pending buffer is non-empty and the disk is busy (typical low-end OEM eMMC under write pressure, device shutting down).

**Before.** cleanup() (StepCountManager.kt:414-420) uses runBlocking with no dispatcher hop, so flushPendingSteps executes the SQLite insert, WAL activity, and SharedPreferences work ON THE MAIN THREAD, blocking until disk I/O completes under the service ANR watchdog.

**Symptom.** ANR ('executing service') on stop/destroy on slow devices; the ANR kill can land between the DB insert (:222) and the prefs write (:225), converting an orderly stop into the EC-6 duplicate or EC-12 loss; also delays device shutdown/reboot.

**After.** Cleanup is a bounded 3 s best-effort flush on IO; skipping it is provably safe (recovery re-derives); no main-thread runBlocking.

### EC-40 &middot; Code bug &middot; Fixed &middot; P2
**Scenario.** A sensor event lands just before stopService/onDestroy: onSensorChanged launches flushPendingSteps on the manager's IO scope; the main thread runs doCleanup -> cleanup() -> database.close(), then stopForeground(STOP_FOREGROUND_REMOVE). The straggler flush finishes afterwards. Second trigger: user immediately restarts tracking after the stop.

**Before.** cleanup() joins ONLY flushJob (StepCountManager.kt:416); threshold flush coroutines launched at :169/:178 belong to a coroutineScope (:66) that is never cancelled, so the comment at :411-412 claiming no coroutine touches the DB is false. The straggler either throws IllegalStateException inside insertStepCount (swallowed, steps vanish, prefs overwritten with the residual) or SQLiteOpenHelper.getWritableDatabase() silently REOPENS the just-closed DB on a leaked connection that coexists with the restarted service's new connection (lock/busy errors then swallowed per EC-4). Its onFlushSuccess also calls NotificationManager.notify after stopForeground removed the FGS notification, leaving a plain ongoing 'Today's Steps: N' notification with no service behind it (BackgroundServiceManager.kt:217-223).

**Symptom.** Loss of the final buffer at service stop, intermittent silently-dropped flushes after every stop/start cycle (leaked connection), and a lingering undismissable stale notification after the user stopped tracking.

**After.** DB never closed; one scope cancelled-and-joined; flush under `stateMutex`; notification updates gated on liveness. Straggler class deleted.

### EC-41 &middot; Lifecycle &middot; Mostly fixed (narrow residual) &middot; P4
**Scenario.** Android 13+ POST_NOTIFICATIONS denied: the FGS starts fine but its notification is never shown, so the user cannot tell tracking is on. Separately, any user can expand the active-apps Task Manager and tap 'Stop app': the process is killed and the system deliberately does NOT honor START_REDELIVER_INTENT for user-initiated Task Manager stops (the app is not put in the stopped state).

**Before.** No POST_NOTIFICATIONS handling anywhere; updateNotification() (BackgroundServiceManager.kt:217-223) silently no-ops when denied. No onTaskRemoved override and no watchdog (alarm/WorkManager) exists to notice the Task Manager kill, so recovery waits for the next boot or app launch.

**Symptom.** Invisible tracking state (user cannot tell it is on, or that it died), unexplained multi-hour gaps, then a catch-up lump; buffered steps at kill are lost.

**After.** onTaskRemoved restart job; `notificationsGranted` in `getTrackingStatus`; resume-notification path.

### EC-42 &middot; Lifecycle &middot; Fixed &middot; P4
**Scenario.** User explicitly stops tracking via stopBackgroundService(); later the phone reboots or the app auto-updates (MY_PACKAGE_REPLACED).

**Before.** BootServiceManager (BootServiceManager.kt:16-30) starts the service unconditionally for every boot/update action; there is no persisted 'tracking enabled' flag anywhere, and stopBackgroundService does not disable the receiver (PackageManager.setComponentEnabledSetting is never used).

**Symptom.** Tracking and the foreground notification silently resurrect after the user turned them off: steps get recorded during periods the user believes tracking was disabled (trust and privacy issue).

**After.** `tracking_enabled` in DPS prefs gates receiver, watchdog job, alarms, and STICKY restarts.

### EC-43 &middot; Lifecycle &middot; Fixed &middot; P4
**Scenario.** A third-party app adds the steps_count plugin from pub and calls startBackgroundService(). The plugin's own manifest (android/src/main/AndroidManifest.xml) is empty: no <service>, no <receiver>, no permissions are merged into the consumer app; everything lives only in the EXAMPLE app's manifest.

**Before.** startForegroundService with an explicit intent for an undeclared service fails to resolve without throwing, so StepsCountPlugin.startBackgroundService still calls result.success(true) (StepsCountPlugin.kt:83-90). No boot receiver exists in the consumer app unless hand-copied from the example.

**Symptom.** Every integrator who does not manually replicate the example manifest ships an app where tracking never starts (and never restarts on boot) while the plugin reports success: service dead by default for all consumers.

**After.** Service, receiver, permissions, uses-feature, and backup rules ship in the plugin manifest and merge into every consumer.

### EC-44 &middot; Code bug &middot; Fixed &middot; P5
**Scenario.** Dart calls getStepCount/getTimeline with startDate=0 (meaning 'from the beginning'), a seconds-based timestamp, or any value that fits in 32 bits; or getTimelineAfter with a small lastSyncTimestamp.

**Before.** StandardMethodCodec delivers Dart ints that fit in 32 bits as java.lang.Integer; call.argument<Long>("startDate") (StepsCountPlugin.kt:154-155, 169-170, 185) performs an unchecked cast to java.lang.Long and throws ClassCastException, converted into result.error. There is no Number-based coercion such as (arg as Number).toLong().

**Symptom.** Legitimate queries fail with STEP_COUNT_ERROR/TIMELINE_ERROR; an app that passes 0 as 'no lower bound' can never read any steps.

**After.** `(argument<Number>)?.toLong()` coercion in all handlers.

### EC-45 &middot; Code bug &middot; Fixed &middot; P5
**Scenario.** App calls getTimelineAfter(null) (documented as 'return the entire timeline') or getTimeline over months. At ~1 row per 50 steps, an active user generates ~200 rows/day, i.e. 70,000+ rows/year.

**Before.** All method-channel handlers run on the platform main thread and perform synchronous SQLite queries plus full List<Map> materialization there (StepsCountPlugin.kt:139-195 -> StepCountManager.kt:319-402 -> StepCountDatabase queries). No background executor, no paging.

**Symptom.** Multi-second UI freezes and ANRs in the host app during sync/history screens; users force-stop the app, which also kills the tracking service (compounding step loss via EC-23).

**After.** All handlers dispatch DB work to `Dispatchers.IO`, reply on main; `seq` paging enables chunked sync.

### EC-46 &middot; Sensor / HW &middot; Fixed &middot; P4
**Scenario.** Device has no TYPE_STEP_COUNTER (still common on entry-level units in the same markets as POCO/Realme), or registerListener returns false (sensor HAL resource limits, transient HAL not-ready after boot).

**Before.** getDefaultSensor returns null and registerSensor silently no-ops via the ?.let (BackgroundServiceManager.kt:134-149); the false-return path only logs a warning with no retry. The health foreground service runs forever showing 'Today's Steps: 0', isServiceRunning() returns true (StepsCountPlugin.kt:129-136), and there is no method-channel signal that step counting is impossible or failed. No uses-feature declaration or capability API exists.

**Symptom.** Service dead but reported as running: app shows permanent 0 with no way to distinguish 'no hardware' from 'no steps'.

**After.** Null sensor / register-false retried with backoff then service self-stops with `no_sensor`; status API distinguishes "no hardware" from "no steps"; uses-feature declared.

### EC-47 &middot; Time / clock &middot; Fixed &middot; P5
**Scenario.** The LOCAL vs UTC API contract is fictional: an integrator reads the docs ('input timestamps are always treated as local time', 'converted to UTC for database query') and pre-shifts their query bounds by the zone offset, or post-shifts returned LOCAL timeline stamps, e.g. in a +05:30 zone. Latent trap: a maintainer 'fixes' the conversion to actually shift values while years of stored rows were written unshifted.

**Before.** convertLocalTimestampToUtc and convertUtcTimestampToLocal are mathematical identities (TimeStampUtils.kt:41-56): Instant -> rezone -> Instant returns the same epoch milli. TimeZoneType.LOCAL and UTC return byte-identical timestamps (StepCountManager.kt:345-360); the misleading comments live at StepCountManager.kt:255-266 and :327. Everything only works because both sides pass unshifted epoch millis, and there is no schema marker recording which time semantics a row was written under (StepCountDatabase.kt:33-39).

**Symptom.** Misattribution: a consumer that honors the documented contract shifts every window by the zone offset (5.5h in India), pushing all midnight-adjacent steps onto the wrong day. Any future 'repair' of the conversion desyncs old rows from new by the zone offset overnight; the fix must preserve identity semantics (pure epoch) and correct the docs instead.

**After.** Converters stay identities; docs corrected to "epoch ms everywhere"; no shifting "fix" ever permitted.


## Low severity (13)

### EC-48 &middot; Sensor / HW &middot; Fixed &middot; P1
**Scenario.** event.values[0] is float32: above 2^24 = 16,777,216 cumulative steps, consecutive integers are unrepresentable and the value quantizes to multiples of 2 (4 above 2^25). Reachable on hubs that persist the count across reboots for the device's lifetime or on year-plus uptimes. Separately, NaN garbage input makes the delta NaN.

**Before.** The Double math at StepCountManager.kt:133 cannot recover precision the HAL already lost in the float, so deltas become lumpy (0 then 2) at high counts; totals are approximately preserved. NaN: Double.roundToInt() throws IllegalArgumentException, caught by the wrapper at :181-183, event skipped and baseline untouched (verified safe by accident).

**Symptom.** Minor precision loss at extreme counts; NaN handled safely only incidentally (the catch also masks real errors); +Infinity feeds the critical EC-1 spike path instead.

**After.** Anchor stores the exact float as REAL; Double delta math; NaN/Inf rejected explicitly pre-math; sub-2^24 quantization lumpiness absorbed by interval attribution (benign).

### EC-49 &middot; Sensor / HW &middot; Fixed &middot; documented
**Scenario.** Negative delta is neither a necessary nor a sufficient reboot signal. Sufficient-side counterexample: the sensor hub restarts mid-session WITHOUT a reboot (hub watchdog reset, thermal reset) and the counter restarts from 0, firing the 'reset' branch. Necessary-side counterexample: Samsung-style hubs persist the step count across reboots, so a real reboot yields a positive or zero delta and the inference never fires.

**Before.** Hub crash: negative delta hits StepCountManager.kt:171-178, is labeled 'Sensor reset', re-baselines, and flushes pending; steps between the last delivered event and the crash are lost (usually small). Samsung persistence: benign today, the baseline stays continuous and counting is correct (:133-155).

**Symptom.** Small loss on hub crashes; more importantly a conceptual trap constraining any future reboot-detection fix: it must use a real boot-session marker (boot count / elapsedRealtime anchor) and handle both hub persistence models, never key off the negative-delta branch.

**After.** Reboot = BOOT_COUNT change only; same-boot negative delta = hub reset: pre-flush delivered steps, credit gated `v` as `gap`, re-anchor; persistent hubs via Case B.

### EC-50 &middot; Sensor / HW &middot; Fixed &middot; P4
**Scenario.** Sensor reports SENSOR_STATUS_UNRELIABLE or accuracy transitions during hub recalibration (seen after firmware updates and hub resets on Vivo/OPPO); value jumps can accompany the unreliable window.

**Before.** onAccuracyChanged is an empty stub (BackgroundServiceManager.kt:237-239); event.accuracy is also never read. Deltas produced during unreliable windows are trusted identically to good data and flow into the same accept-up-to-500K path (StepCountManager.kt:143-155).

**Symptom.** Occasional phantom jumps accepted with no evidence trail; cheap to at least log accuracy alongside batch_detected.

**After.** `onAccuracyChanged` logged; UNRELIABLE-window rows flagged LOW_ACCURACY; jumps still rate-gated.

### EC-51 &middot; Sensor / HW &middot; Fixed &middot; P4
**Scenario.** Duplicate or same-value events: OEM HALs re-deliver the current cumulative value on registration heartbeats or connection re-syncs; a second in-process TYPE_STEP_COUNTER listener (another plugin in the host app) changes effective delivery rate/batching for the shared sensor.

**Before.** Verified non-bug for counting: delta 0 falls through both branches (StepCountManager.kt:135 and :171) with no accumulation. Side effects only: stepCountChannel?.invokeMethod fires per event (:180) and saveBaseline rewrites prefs per event (:109-115, every nonzero delta), both on the main thread since registerListener with a null Handler delivers on the main looper.

**Symptom.** None to counts; minor main-thread churn during dense event streams.

**After.** Zero delta = heartbeat, memory-only; events on a HandlerThread; no per-event prefs writes exist; channel invokes throttled.

### EC-52 &middot; Code bug &middot; Fixed &middot; P2
**Scenario.** The overflow cap is get-then-add, not atomic: with pending near MAX_PENDING_STEPS (reachable while draining a huge deferred backlog), the main thread reads current, a flush getAndSet(0) empties the buffer, then addCapped computed against the stale current truncates or fully drops the new delta despite the buffer being empty. Separately, the failure-restore path adds back with no cap; once pending exceeds MAX_PENDING_STEPS, the cap arithmetic yields a negative addCapped and every subsequent delta is dropped entirely.

**Before.** StepCountManager.kt:158-166: pendingSteps.get() at :158 and addAndGet at :161 are separate operations racing the getAndSet at :206; the restore at :233-234 bypasses the cap.

**Symptom.** Silent partial loss in exactly the backlogged regime the cap was added to protect.

**After.** Pending cap and get-then-add arithmetic deleted; credit derives from counter arithmetic in one place under the mutex.

### EC-53 &middot; Code bug &middot; Mostly fixed (narrow residual) &middot; P5
**Scenario.** A UI or sync query lands between the flush's getAndSet(0) and the insert commit: the reader sees neither the pending buffer (already zeroed) nor the new rows (not yet committed).

**Before.** flush does pendingSteps.getAndSet(0) at StepCountManager.kt:206, then inserts at :221-222; getTodaysCount (:301-303) and getStepCount (:268-272) add pendingSteps.get() to an uncoordinated DB read, so the reported total drops by up to the in-flight amount (up to 50,000 after a batch) and then jumps back.

**Symptom.** Visible count regression for one reading: a regressing step counter is a classic trust-destroying report even though no data is lost; change-triggered sync layers can persist the dip.

**After.** Readers share `stateMutex` with flush; totals are monotone through a flush.

### EC-54 &middot; Code bug &middot; Fixed &middot; P3
**Scenario.** Inclusive-end range double count: an app sums contiguous windows [day1Start, day2Start] and [day2Start, day3Start], and a flush row lands exactly on the boundary millisecond (the 60s timer makes ms-exact midnight possible).

**Before.** buildDateQuery uses 'timestamp >= ? AND timestamp <= ?', inclusive on BOTH ends (StepCountDatabase.kt:301-305), so the boundary row satisfies both adjacent queries; the plugin docs also describe endDate as inclusive (lib/steps_count.dart:83) with no half-open convention documented or enforced.

**Symptom.** Occasional double-counted row (up to 50,000 steps) when apps use natural [start, nextStart] chunking; weekly sum exceeds the timeline sum.

**After.** Midnight-split rows end at `midnight - 1 ms`; strictly-increasing timestamps; half-open internal windows; boundary rows satisfy exactly one window; half-open chunking documented.

### EC-55 &middot; Time / clock &middot; Fixed &middot; P3
**Scenario.** DST boundary anomalies. Fall-back landing before midnight (00:00 jumping back to 23:00, e.g. historical America/Sao_Paulo rules in some OEM tzdata): the 23:00-23:59 hour occurs twice and atZone() resolves the ambiguous 23:59:59.999 to the EARLIER offset, so epoch stamps from the repeated hour's second pass fall strictly between yesterday's window end and today's window start. Spring-forward at midnight (zones where 00:00 does not exist): the plugin resolves start-of-day via java.time gap rules while the Flutter app computes its own boundary with Dart's DateTime, whose nonexistent-time resolution differs per platform.

**Before.** getTodaysTimestamp builds boundaries via LocalDate.atTime and atZone(systemDefault) (TimeStampUtils.kt:25-34); app-side ranges arrive as raw epoch millis from Dart (lib/steps_count_method_channel.dart:74-78) and are used as-is because the converters are identity (TimeStampUtils.kt:41-45).

**Symptom.** Up to an hour of steps matching neither day's window forever (fall-back), and plugin-vs-app disagreement of up to one hour on the same day's total (spring-forward); day sums stop reconciling with the timeline.

**After.** `atStartOfDay(zone)` half-open windows tile fall-back and spring-forward exactly; write-time splitting means no row straddles the anomalous hour.

### EC-56 &middot; Lifecycle &middot; Mitigated (documented limit) &middot; documented
**Scenario.** Multi-user and work profile: the hardware TYPE_STEP_COUNTER is one global per-device counter, but each user/profile gets its own prefs, DB, service, and boot receiver. BYOD user installs the app in both personal and work profile, or a secondary user runs it. Work-profile pause / user switch suspends sensor delivery for the background profile, then resumes.

**Before.** No user/profile awareness anywhere; both instances independently baseline the same physical counter and record the same physical steps (StepCountManager.kt:122-184). Suspension gaps become single catch-up deltas stamped at switch-back (:221).

**Symptom.** Same steps counted twice when both profiles sync to one account; lump misattribution at profile switch/unpause. Rare configuration.

**After.** Documented limitation (one physical counter, per-profile instances); `boot_count` + `install_id` in rows let a backend detect same-device duplicates.

### EC-57 &middot; Code bug &middot; Fixed &middot; P5
**Scenario.** The static MethodChannel outlives the Flutter engine: onAttachedToEngine assigns StepCountManager.stepCountChannel (StepsCountPlugin.kt:29); the user backs out of the app, the engine is destroyed, the FGS keeps running and invokes the channel on every sensor event. A second engine (add-to-app, background isolate) overwrites the static and is never restored on its own detach.

**Before.** onDetachedFromEngine clears only the call handler, never the static channel (StepsCountPlugin.kt:264-266); StepCountManager.kt:180 keeps calling invokeMethod on the dead engine's messenger. The throw is swallowed by the catch at :181-183 after buffering completes, so counts survive, but the destroyed engine and messenger are pinned by the static for the service's lifetime.

**Symptom.** Engine memory leak and per-event exception overhead in the long-running service; with mismatched multi-engine ordering, live UI stops receiving onSensorChanged updates (display staleness, not DB corruption).

**After.** Static channel cleared on matching engine detach.

### EC-58 &middot; Lifecycle &middot; Fixed &middot; -
**Scenario.** Stop-path fragility: (a) stopBackgroundService delivers the stop via context.startService(FORCE_STOP); if the FGS already died and the app is backgrounded, startService throws IllegalStateException on API 26+ and the stop is lost. (b) If the app is foregrounded and the service is NOT running, the FORCE_STOP intent CREATES the full service: onCreate builds StepCountManager, loads stale prefs pending>0, and cleanup's final flush inserts those steps as a row stamped 'now' even though tracking was off. (c) onStartCommand returns START_REDELIVER_INTENT even for FORCE_STOP, so a kill during cleanup resurrects the service and immediately tears it down again.

**Before.** Stop-by-startService at BackgroundServiceManager.kt:36-41; unconditional START_REDELIVER_INTENT at :77; exception caught at StepsCountPlugin.kt:124-126; no stopService()/component-disable fallback.

**Symptom.** User-requested stops silently fail from background contexts (service keeps running against intent), 'stop' of a stopped service fabricates a misattributed step row, and the service flaps after kills during stop.

**After.** Flag-first stop + `stopService()` fallback + `START_NOT_STICKY` after stop; a stopped service cannot fabricate rows (no pending buffer exists).

### EC-59 &middot; Code bug &middot; Mostly fixed (narrow residual) &middot; P5
**Scenario.** exportStepsDatabase (the diagnostic tool for investigating these very spikes) runs while the service is actively flushing, or after runWalCheckpointFull returns false.

**Before.** StepsCountPlugin.kt:224-249 does checkpoint-then-copy with no write quiescence; commits after the checkpoint land in the -wal file, which copyFile (:256-262) never copies (no -wal/-shm handling), so the export silently misses the newest rows; a concurrent checkpoint can rewrite the main file mid-copy, producing a torn export. The manager-null fallback also opens a second READWRITE connection to the live file (:226-240), inviting busy/locked errors on the write side that EC-4 swallows. The method returns success with the file path regardless.

**Symptom.** Stale or corrupt diagnostic exports reported as success (undermining the spike investigation), plus a narrow window for live silent loss.

**After.** Export under `stateMutex`: VACUUM INTO (API 30+) or checkpoint + db/-wal/-shm copy, integrity-checked; failures loud.

### EC-60 &middot; Persistence &middot; Fixed &middot; P2
**Scenario.** User rolls back to an older APK build (Play staged-rollout rollback, sideload) whose code predates DB v2 while step_count.db on disk is already version 2.

**Before.** StepCountDatabase does not override onDowngrade, so SQLiteOpenHelper throws SQLiteException('Can't downgrade database') on every open; every read returns 0/empty and every insert returns null, all swallowed (StepCountDatabase.kt:145-148, 199-202).

**Symptom.** App shows zero steps and records nothing, permanently, even though the data file is intact; no error surfaces anywhere.

**After.** `onDowngrade` no-op preserving data; v3 keeps the `timestamp` column name so older readers still see rows.


## The five documented limits

Honest ceilings of a sensor-only design, each mitigated and (where possible) marked in the data:

- **EC-37 Vibration miscounts**: cheap hubs count road vibration as steps at the hardware level; the rate gate clips only impossible bursts. Per-event cadence is logged for future heuristics.
- **EC-38 Frozen-but-delivering hub**: detected by the progress watchdog and re-registered, but steps lost inside the freeze window are unrecoverable.
- **EC-23 Force-stop gaps**: a user force-stop blocks restart until the app is next opened; the outage is bounded and marked, not erased.
- **EC-56 Multi-profile duplication**: one physical counter, one instance per profile; rows carry boot/install ids so a backend can detect duplicates.
- **EC-36 Timezone re-bucketing**: historical day totals recompute under a new zone after travel; epoch-pure rows re-bucket honestly.

**Deferred enhancements** (tracked, not blocking): a seq-based sync cursor (needs one more migration) and a JobScheduler + tap-to-resume notification for the Android 12+ background-start limit.

Phases: P1 anti-spike gates &middot; P2 exactly-once durability &middot; P3 interval time-attribution &middot; P4 lifecycle + direct-boot &middot; P5 query API & observability.
