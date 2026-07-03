package com.dajiraj.steps_count

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import io.flutter.plugin.common.MethodChannel
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Manages step counting logic and database operations.
 *
 * Phase 2 durability model (the "anchor is a cursor into the hardware WAL" design):
 *
 *  - Within a boot session the TYPE_STEP_COUNTER cumulative counter IS a write-ahead log. The DB
 *    holds a single durable cursor into it (the anchor). Every flush inserts the step rows AND
 *    advances the anchor in ONE SQLite transaction, so steps are credited exactly once.
 *  - Unflushed steps are DERIVED: `unflushed = lastEventCounter - anchorCounter`. Losing the
 *    in-memory buffer to a process kill costs nothing; the hardware re-delivers the same cumulative
 *    value and the delta is recomputed. So in-session loss is zero regardless of OEM kills (EC-12),
 *    and a DB write failure just leaves the anchor unmoved and retries (EC-4).
 *  - SharedPreferences is out of the counting path entirely, which removes the prefs-vs-DB
 *    durability races (EC-5/EC-6/EC-13/EC-14). All DB work runs on a single-threaded dispatcher, so
 *    flushes are single-flight by construction and never overlap (EC-13/EC-40).
 *
 * Phase 1 anti-spike defenses are retained: garbage gate (EC-1/EC-48), boot-session anchoring
 * (EC-2/EC-11), and the rate gate + quarantine (EC-3). Honest limits still open for later phases:
 * steps are stamped at flush time, not event time (attribution is Phase 3); storage is still
 * credential-encrypted (device-protected storage + direct boot is Phase 4). See
 * docs/robust_step_counting_spec.md.
 */
class StepCountManager(context: Context, private val onFlushSuccess: () -> Unit = {}) {
    companion object {
        private const val TAG = "StepCountManager"
        private const val PREFS_NAME = "steps_count_prefs"
        // Legacy Phase 1 prefs keys, read once to migrate into the tracker_state anchor.
        private const val KEY_LAST_SENSOR_VALUE = "last_sensor_value"
        private const val KEY_IS_INITIALIZED = "is_initialized"
        private const val KEY_PENDING_STEPS = "pending_steps"
        private const val KEY_BOOT_ID = "boot_id"
        private const val KEY_MIGRATED_TO_ANCHOR = "migrated_to_anchor_v2"

        // Flush thresholds
        private const val FLUSH_STEP_THRESHOLD = 50
        private const val FLUSH_INTERVAL_MS = 60_000L // 60 seconds

        /**
         * Garbage gate (EC-1/EC-48): reject non-finite/negative/absurd cumulative values BEFORE
         * computing a delta. 1e9 is ~4 steps/sec for 8 years of continuous uptime, comfortably above
         * any real lifetime count while still rejecting uint32-as-float (4.29e9), Float.MAX_VALUE and
         * +Infinity. Replaces the old "cap the delta to 500k and add it as real steps" spike source.
         */
        private const val GARBAGE_ABS_MAX = 1.0e9f

        /**
         * Rate gate (EC-3): the physically-plausible step ceiling for an elapsed interval. Burst
         * ceiling of 5 steps/sec (above world-record cadence) for the first hour, then 1.2 steps/sec
         * sustained, plus a small constant slack. Replaces the static MAX_REASONABLE_DELTA = 500_000.
         */
        private const val RATE_BURST_PER_SEC = 5.0
        private const val RATE_SUSTAINED_PER_SEC = 1.2
        private const val RATE_BASE_SLACK = 60L
        private const val RATE_BURST_WINDOW_SEC = 3600L

        /**
         * Quarantine (EC-1/EC-3): when a delta is implausible we freeze the anchor and credit nothing.
         * If the high value persists for this many mutually-consistent events across at least this much
         * time, it is treated as a genuine (rare) hardware re-baseline and we re-anchor with zero credit.
         */
        private const val QUARANTINE_CONFIRM_COUNT = 3
        private const val QUARANTINE_CONFIRM_MS = 600_000L // 10 minutes

        /** Cap per DB row for hygiene; a large credit is split into several rows in one transaction. */
        private const val MAX_STEPS_PER_ROW = 50_000

        /** Delta above this is logged as batch_detected (OEM batching evidence; logs only). */
        private const val BATCH_DETECTION_THRESHOLD = FLUSH_STEP_THRESHOLD * 5

        /** Upper bound on the blocking final flush at stop, so a slow disk cannot ANR the service. */
        private const val FINAL_FLUSH_TIMEOUT_MS = 2_000L

        var stepCountChannel: MethodChannel? = null

        /**
         * Physically-plausible maximum steps for an elapsed interval of [dtSec] seconds. Pure function,
         * exposed for unit testing. Examples: 1s -> 65, 60s -> 360, 1h -> 18,060, 24h -> 117,420,
         * 10 days -> 1,050,540 (so a genuine multi-day catch-up passes while 50k-in-2s is rejected).
         */
        fun plausibleMax(dtSec: Long): Long {
            val dt = dtSec.coerceAtLeast(0)
            val burst = minOf(dt, RATE_BURST_WINDOW_SEC)
            val sustained = maxOf(0L, dt - RATE_BURST_WINDOW_SEC)
            return RATE_BASE_SLACK + (RATE_BURST_PER_SEC * burst).toLong() + (RATE_SUSTAINED_PER_SEC * sustained).toLong()
        }

        /** True if [v] is a usable cumulative sensor reading. Pure function, exposed for unit testing. */
        fun isAcceptableSensorValue(v: Float): Boolean = v.isFinite() && v >= 0f && v <= GARBAGE_ABS_MAX

        /**
         * Steps to credit at a flush given the latest cumulative value and the durable anchor. Pure
         * function, exposed for unit testing. NaN operands (no anchor yet) yield 0.
         */
        fun computeCredit(lastEventCounter: Double, anchorCounter: Double): Long {
            if (lastEventCounter.isNaN() || anchorCounter.isNaN()) return 0
            return floor(lastEventCounter - anchorCounter).toLong().coerceAtLeast(0)
        }

        /**
         * Split a credit into per-row chunks of at most [maxPerRow] (DB hygiene). Pure function,
         * exposed for unit testing. The chunk sum always equals [credit].
         */
        fun splitIntoRowChunks(credit: Long, maxPerRow: Int): List<Int> {
            if (credit <= 0 || maxPerRow <= 0) return emptyList()
            val chunks = ArrayList<Int>()
            var remaining = credit
            while (remaining > 0) {
                val c = minOf(remaining, maxPerRow.toLong()).toInt()
                chunks.add(c)
                remaining -= c
            }
            return chunks
        }
    }

    // Never-closed process singleton (EC-40). Phase 4 will pass a device-protected-storage context.
    private val database = StepCountDatabase.getInstance(context)
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // H2: SupervisorJob so a failing child coroutine does not cancel the flush loop.
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // All DB mutations run here: at most one at a time, so flushes are single-flight and never overlap
    // (EC-13). A Mutex additionally guards the read-modify-write of the anchor mirror across query reads.
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val stateMutex = Mutex()

    // Boot-session identity for THIS process (constant for the process lifetime).
    private val thisBootId: String = computeBootId(context.applicationContext)

    // Durable anchor mirror (authoritative copy is tracker_state in SQLite). Written only on
    // dbDispatcher after a committed transaction; read from other threads, hence @Volatile.
    @Volatile private var anchorCounter: Double = Double.NaN // NaN = no anchor yet
    @Volatile private var anchorElapsedMs: Long = 0L
    @Volatile private var anchorWallMs: Long = 0L
    @Volatile private var anchorBootId: String = ""
    @Volatile private var lastRowEndMs: Long = 0L

    // Latest accepted event (non-authoritative; re-derivable from the hardware counter). Written on
    // the sensor thread, read on dbDispatcher, hence @Volatile.
    @Volatile private var lastEventCounter: Double = Double.NaN
    @Volatile private var lastEventElapsedMs: Long = -1L

    // Quarantine state for implausible deltas (EC-3): read/written only on the sensor thread.
    private var quarantineActive = false
    private var quarantineCount = 0
    private var quarantineValue = 0f
    private var quarantineFirstElapsedMs = 0L
    private var quarantineLastElapsedMs = 0L

    // Periodic flush job
    private var flushJob: Job? = null

    init {
        recoverOnStart()
        startPeriodicFlush()
    }

    // ---- Startup recovery -----------------------------------------------------------------------

    /**
     * Load the durable anchor and reconcile it with the current boot session. Migrates any Phase 1
     * prefs baseline into the anchor the first time this build runs.
     */
    private fun recoverOnStart() {
        migrateLegacyPrefsIfNeeded()

        val st = database.readTrackerState()
        if (st == null || st.anchorCounter.isNaN()) {
            // No anchor yet: the first event will anchor with zero credit.
            anchorCounter = Double.NaN
            lastEventCounter = Double.NaN
            Log.d(TAG, "recoverOnStart: NO_ANCHOR (fresh)")
            return
        }

        if (st.bootId.isEmpty() || st.bootId == thisBootId) {
            // Same boot session (or a legacy anchor with no boot id): trust it. The first event's
            // delta re-derives any steps that were unflushed when the process last died (EC-12).
            anchorCounter = st.anchorCounter
            anchorElapsedMs = st.anchorElapsedMs
            anchorWallMs = st.anchorWallMs
            anchorBootId = if (st.bootId.isEmpty()) thisBootId else st.bootId
            lastRowEndMs = st.lastRowEndMs
            lastEventCounter = st.anchorCounter
            lastEventElapsedMs = st.anchorElapsedMs
            Log.d(TAG, "recoverOnStart: ANCHORED at ${st.anchorCounter} (boot=$anchorBootId)")
        } else {
            // Different boot session or device (missed reboot, restore, reinstall). The anchor is
            // radioactive (EC-2/EC-11): void it so the first event re-anchors with zero credit. Step
            // history rows are kept (real data).
            Log.w(TAG, "recoverOnStart: boot/device changed (${st.bootId} -> $thisBootId); voiding anchor")
            anchorCounter = Double.NaN
            lastEventCounter = Double.NaN
        }
    }

    /**
     * One-time migration of the Phase 1 SharedPreferences baseline into the tracker_state anchor.
     * Preserves any un-flushed Phase 1 steps by booking them as a row, and seeds the anchor so the
     * next delta continues from the old baseline without a spike or a loss.
     */
    private fun migrateLegacyPrefsIfNeeded() {
        if (prefs.getBoolean(KEY_MIGRATED_TO_ANCHOR, false)) return
        val existing = database.readTrackerState()
        val wasInitialized = prefs.getBoolean(KEY_IS_INITIALIZED, false)

        if (existing != null && !existing.anchorCounter.isNaN()) {
            // An anchor already exists (nothing to migrate); just mark done.
            prefs.edit().putBoolean(KEY_MIGRATED_TO_ANCHOR, true).apply()
            return
        }

        if (wasInitialized) {
            val baseline = prefs.getFloat(KEY_LAST_SENSOR_VALUE, 0f).toDouble()
            val pending = prefs.getInt(KEY_PENDING_STEPS, 0).coerceAtLeast(0)
            val oldBootId = prefs.getString(KEY_BOOT_ID, "") ?: ""
            val now = TimeStampUtils.getCurrentUtcTimestamp()
            // Book any un-flushed Phase 1 steps so they are not lost, then anchor at the old baseline.
            val chunks = splitIntoRowChunks(pending.toLong(), MAX_STEPS_PER_ROW)
            val seeded = AnchorState(
                anchorCounter = baseline,
                anchorElapsedMs = 0L,
                anchorWallMs = now,
                bootId = if (oldBootId.isNotEmpty()) oldBootId else thisBootId,
                lastRowEndMs = now
            )
            val ok = database.commitFlush(chunks, now, seeded)
            Log.d(TAG, "Legacy prefs migrated: baseline=$baseline pending=$pending ok=$ok")
        }

        prefs.edit()
            .putBoolean(KEY_MIGRATED_TO_ANCHOR, true)
            .remove(KEY_LAST_SENSOR_VALUE)
            .remove(KEY_PENDING_STEPS)
            .remove(KEY_IS_INITIALIZED)
            .remove(KEY_BOOT_ID)
            .apply()
    }

    private fun computeBootId(ctx: Context): String {
        val cr = ctx.contentResolver
        val bootCount = try {
            Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT, -1)
        } catch (e: Exception) {
            -1
        }
        val androidId = try {
            Settings.Secure.getString(cr, Settings.Secure.ANDROID_ID) ?: ""
        } catch (e: Exception) {
            ""
        }
        return "$bootCount|$androidId"
    }

    private fun clearQuarantine() {
        quarantineActive = false
        quarantineCount = 0
    }

    private fun unflushed(): Long = computeCredit(lastEventCounter, anchorCounter)

    // ---- Sensor ingestion (sensor callback thread) ----------------------------------------------

    /**
     * Process new sensor data.
     *
     * @param sensorValue The raw cumulative value from TYPE_STEP_COUNTER (steps since boot).
     * @param eventTimestampNanos The hardware event timestamp (ns on the elapsedRealtime clock), used
     *   to rate-gate the delta by real elapsed time; 0 (unknown) falls back to "now".
     */
    fun onSensorChanged(sensorValue: Float, eventTimestampNanos: Long = 0L) {
        try {
            // (1) Garbage gate (EC-1/EC-48).
            if (!isAcceptableSensorValue(sensorValue)) {
                Log.w(TAG, "garbage_value raw=$sensorValue rejected (anchor untouched)")
                return
            }

            val nowElapsed = SystemClock.elapsedRealtime()
            val evElapsed = eventTimestampNanos / 1_000_000
            val elapsedMs = if (evElapsed in 1..(nowElapsed + 10_000)) evElapsed else nowElapsed
            val v = sensorValue.toDouble()

            // (2) No anchor yet (fresh install, corruption recovery, or voided radioactive baseline):
            // anchor here with zero credit.
            if (anchorCounter.isNaN()) {
                anchorHere(v, elapsedMs)
                Log.d(TAG, "Anchored (zero credit) at $v (boot=$thisBootId)")
                return
            }

            // (3) Defensive in-process boot/device change (recoverOnStart normally handles this).
            if (anchorBootId.isNotEmpty() && anchorBootId != thisBootId) {
                Log.w(TAG, "boot/device changed in-process ($anchorBootId -> $thisBootId); re-anchoring")
                anchorHere(v, elapsedMs)
                return
            }

            val ref = if (lastEventCounter.isNaN()) anchorCounter else lastEventCounter
            val refElapsed = if (lastEventElapsedMs >= 0) lastEventElapsedMs else anchorElapsedMs
            val delta = (v - ref).roundToInt()

            if (delta == 0) {
                stepCountChannel?.invokeMethod("onSensorChanged", null) // heartbeat (EC-51)
                return
            }

            if (delta < 0) {
                // Negative delta without a boot change = in-session hardware counter reset (EC-49).
                // Flush what was already delivered, then re-anchor to the new low value (zero credit
                // for the reset gap). lastEventCounter is left high so the flush credits the real steps.
                Log.w(TAG, "counter_reset ref=$ref new=$v; flush then re-anchor")
                clearQuarantine()
                coroutineScope.launch(dbDispatcher) {
                    flushLocked()
                    anchorToLocked(v, elapsedMs)
                }
                stepCountChannel?.invokeMethod("onSensorChanged", null)
                return
            }

            if (delta > BATCH_DETECTION_THRESHOLD) {
                Log.d(TAG, "batch_detected delta=$delta (threshold=$BATCH_DETECTION_THRESHOLD)")
            }

            // (4) Rate gate (EC-3).
            val dtSec = if (refElapsed in 0 until elapsedMs) (elapsedMs - refElapsed) / 1000 else 0L
            val cap = plausibleMax(dtSec)
            if (delta > cap) {
                handleImplausibleDelta(sensorValue, elapsedMs, delta, dtSec, cap)
                return // anchor NOT advanced, nothing credited
            }

            // (5) Plausible: accept. Advancing lastEventCounter increases derived unflushed; the flush
            // books it and advances the anchor transactionally.
            clearQuarantine()
            lastEventCounter = v
            lastEventElapsedMs = elapsedMs
            if (unflushed() >= FLUSH_STEP_THRESHOLD) {
                coroutineScope.launch(dbDispatcher) { flushLocked() }
            }
            stepCountChannel?.invokeMethod("onSensorChanged", null)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing sensor data: ${e.message}")
        }
    }

    /**
     * Handle a delta that exceeded the physical-rate cap. The anchor is FROZEN and nothing is
     * credited (no cap-and-add). A persistent high value (QUARANTINE_CONFIRM_COUNT consistent events
     * over QUARANTINE_CONFIRM_MS) is adopted as a genuine hardware re-baseline with zero credit.
     */
    private fun handleImplausibleDelta(sensorValue: Float, elapsedMs: Long, delta: Int, dtSec: Long, cap: Long) {
        val gapSinceQuarantine = ((elapsedMs - quarantineLastElapsedMs) / 1000).coerceAtLeast(0)
        val consistentContinuation = quarantineActive &&
            sensorValue >= quarantineValue &&
            (sensorValue.toDouble() - quarantineValue.toDouble()) <= plausibleMax(gapSinceQuarantine)

        if (consistentContinuation) {
            quarantineCount++
            quarantineValue = sensorValue
            quarantineLastElapsedMs = elapsedMs
        } else {
            quarantineActive = true
            quarantineCount = 1
            quarantineValue = sensorValue
            quarantineFirstElapsedMs = elapsedMs
            quarantineLastElapsedMs = elapsedMs
        }

        Log.w(TAG, "implausible_delta d=$delta dt=${dtSec}s cap=$cap raw=$sensorValue " +
                   "(quarantine $quarantineCount/$QUARANTINE_CONFIRM_COUNT)")

        if (quarantineCount >= QUARANTINE_CONFIRM_COUNT &&
            (elapsedMs - quarantineFirstElapsedMs) >= QUARANTINE_CONFIRM_MS) {
            Log.w(TAG, "quarantine confirmed; re-anchoring to $sensorValue with zero credit")
            clearQuarantine()
            anchorHere(sensorValue.toDouble(), elapsedMs)
        }
    }

    /** Set the anchor to [v] with zero credit (sensor thread): update the mirror now, persist async. */
    private fun anchorHere(v: Double, elapsedMs: Long) {
        anchorCounter = v
        anchorElapsedMs = elapsedMs
        anchorWallMs = TimeStampUtils.getCurrentUtcTimestamp()
        anchorBootId = thisBootId
        lastEventCounter = v
        lastEventElapsedMs = elapsedMs
        clearQuarantine()
        coroutineScope.launch(dbDispatcher) { anchorToLocked(v, elapsedMs) }
        stepCountChannel?.invokeMethod("onSensorChanged", null)
    }

    // ---- Flush and anchor (dbDispatcher: serialized, single-flight) ------------------------------

    private fun startPeriodicFlush() {
        flushJob = coroutineScope.launch(dbDispatcher) {
            while (true) {
                delay(FLUSH_INTERVAL_MS)
                flushLocked()
            }
        }
    }

    /**
     * Book the derived credit (lastEventCounter - anchorCounter) by inserting rows and advancing the
     * anchor in one transaction. Runs only on dbDispatcher (single-flight). On failure the anchor is
     * not advanced, so the credit stays derivable and is retried on the next flush (EC-4/EC-6/EC-14).
     */
    private suspend fun flushLocked() = stateMutex.withLock {
        val last = lastEventCounter
        val anchor = anchorCounter
        val credit = computeCredit(last, anchor)
        if (credit <= 0) return@withLock

        val now = TimeStampUtils.getCurrentUtcTimestamp()
        val chunks = splitIntoRowChunks(credit, MAX_STEPS_PER_ROW)
        val newAnchor = AnchorState(
            anchorCounter = last,
            anchorElapsedMs = lastEventElapsedMs,
            anchorWallMs = now,
            bootId = thisBootId,
            lastRowEndMs = now
        )
        val ok = database.commitFlush(chunks, now, newAnchor)
        if (ok) {
            anchorCounter = last
            anchorElapsedMs = lastEventElapsedMs
            anchorWallMs = now
            anchorBootId = thisBootId
            lastRowEndMs = now
            Log.d(TAG, "Flushed $credit steps at $now (UTC). anchor=$last")
            // Notification refresh is outside the transaction; a throw here cannot re-flush (EC-14).
            try {
                onFlushSuccess()
            } catch (e: Exception) {
                Log.e(TAG, "onFlushSuccess (notification refresh) failed: ${e.message}")
            }
        } else {
            Log.e(TAG, "Flush failed (rolled back); $credit steps stay derivable, will retry")
        }
    }

    /** Re-anchor to [v] with zero credit (dbDispatcher): persist the anchor, then mirror it. */
    private suspend fun anchorToLocked(v: Double, elapsedMs: Long) = stateMutex.withLock {
        val now = TimeStampUtils.getCurrentUtcTimestamp()
        val newAnchor = AnchorState(
            anchorCounter = v,
            anchorElapsedMs = elapsedMs,
            anchorWallMs = now,
            bootId = thisBootId,
            lastRowEndMs = maxOf(lastRowEndMs, 0L)
        )
        if (database.setAnchor(newAnchor)) {
            anchorCounter = v
            anchorElapsedMs = elapsedMs
            anchorWallMs = now
            anchorBootId = thisBootId
            lastEventCounter = v
            lastEventElapsedMs = elapsedMs
        } else {
            Log.e(TAG, "anchorTo failed to persist; keeping in-memory mirror ($v)")
            anchorCounter = v
            lastEventCounter = v
            lastEventElapsedMs = elapsedMs
        }
    }

    fun runWalCheckpointForExport(): Boolean = database.runWalCheckpointFull()

    // ---- Queries (binder/main thread) -----------------------------------------------------------

    /**
     * Get total step count for a date range.
     * @param startDate Start date in local milliseconds (nullable). @param endDate likewise.
     * @return Total steps in the range, including derived unflushed steps when the range includes now.
     */
    fun getStepCount(startDate: Long? = null, endDate: Long? = null): Int {
        return try {
            val startUtc = startDate?.let { TimeStampUtils.convertLocalTimestampToUtc(it) }
            val endUtc = endDate?.let { TimeStampUtils.convertLocalTimestampToUtc(it) }
            val dbSteps = database.getStepCount(startUtc, endUtc)
            val nowUtc = TimeStampUtils.getCurrentUtcTimestamp()
            val rangeIncludesNow = (startUtc == null || startUtc <= nowUtc) &&
                (endUtc == null || endUtc >= nowUtc)
            val total = dbSteps + if (rangeIncludesNow) unflushed().toInt() else 0
            Log.d(TAG, "Step count query - DB: $dbSteps, unflushed: ${unflushed()}, total: $total")
            total
        } catch (e: Exception) {
            Log.e(TAG, "Error getting step count: ${e.message}")
            0
        }
    }

    /**
     * Get today's step count (00:00 - 23:59 local) from the DB plus derived unflushed steps.
     */
    fun getTodaysCount(): Int {
        return try {
            val startLocal = TimeStampUtils.getTodaysTimestamp(true)
            val endLocal = TimeStampUtils.getTodaysTimestamp(false)
            val startUtc = TimeStampUtils.convertLocalTimestampToUtc(startLocal)
            val endUtc = TimeStampUtils.convertLocalTimestampToUtc(endLocal)
            val dbSteps = database.getStepCount(startUtc, endUtc)
            val total = dbSteps + unflushed().toInt()
            Log.d(TAG, "Today's step count - DB: $dbSteps, unflushed: ${unflushed()}, total: $total")
            total
        } catch (e: Exception) {
            Log.e(TAG, "Error getting today's step count: ${e.message}")
            0
        }
    }

    /**
     * Get timeline data (list of step entries with timestamps).
     */
    fun getTimeline(
        startDate: Long? = null, endDate: Long? = null, timeZone: TimeZoneType = TimeZoneType.LOCAL
    ): List<Map<String, Any>> {
        return try {
            val startUtc = startDate?.let { TimeStampUtils.convertLocalTimestampToUtc(it) }
            val endUtc = endDate?.let { TimeStampUtils.convertLocalTimestampToUtc(it) }
            val dbTimelineData = database.getTimelineData(startUtc, endUtc)
            val responseData = dbTimelineData.map { entry ->
                val utcTimestamp = entry["timestamp"] as Long
                val stepCount = entry["step_count"] as Int
                val uuid = entry["uuid"] as? String
                val responseTimestamp = if (timeZone.isLocal) {
                    TimeStampUtils.convertUtcTimestampToLocal(utcTimestamp)
                } else {
                    utcTimestamp
                }
                val result = mutableMapOf<String, Any>(
                    "step_count" to stepCount, "timestamp" to responseTimestamp
                )
                if (uuid != null) result["uuid"] = uuid
                result
            }
            Log.d(TAG, "Timeline query - Total entries: ${responseData.size}")
            responseData
        } catch (e: Exception) {
            Log.e(TAG, "Error getting timeline data: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get all timeline entries recorded strictly after [lastSyncTimestamp] (UTC ms), or all if null.
     */
    fun getTimelineAfter(lastSyncTimestamp: Long?): List<Map<String, Any>> {
        return try {
            val dbData = if (lastSyncTimestamp != null) {
                database.getTimelineDataAfter(lastSyncTimestamp)
            } else {
                database.getTimelineData()
            }
            val responseData = dbData.map { entry ->
                val mutableEntry = mutableMapOf<String, Any>(
                    "step_count" to (entry["step_count"] as Int),
                    "timestamp" to (entry["timestamp"] as Long)
                )
                (entry["uuid"] as? String)?.let { mutableEntry["uuid"] = it }
                mutableEntry
            }
            Log.d(TAG, "getTimelineAfter - returned ${responseData.size} entries")
            responseData
        } catch (e: Exception) {
            Log.e(TAG, "Error in getTimelineAfter: ${e.message}")
            emptyList()
        }
    }

    /**
     * Clean up on service stop/destroy. Stops the periodic flush, does ONE bounded best-effort final
     * flush (so a clean stop persists the last steps), then cancels the scope so this dying manager
     * can never race a freshly-started one on the shared DB singleton.
     *
     * The flush is bounded by FINAL_FLUSH_TIMEOUT_MS to avoid an ANR on a slow disk (EC-39); timing
     * out loses nothing durable because unflushed steps re-derive from the hardware counter on the
     * next start (EC-12). The DB singleton is intentionally never closed (EC-40).
     */
    fun cleanup() {
        flushJob?.cancel()
        try {
            runBlocking(dbDispatcher) {
                withTimeoutOrNull(FINAL_FLUSH_TIMEOUT_MS) { flushLocked() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "final flush during cleanup failed: ${e.message}")
        }
        coroutineScope.cancel()
    }
}
