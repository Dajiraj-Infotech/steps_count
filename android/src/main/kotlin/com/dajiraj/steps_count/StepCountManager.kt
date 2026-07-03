package com.dajiraj.steps_count

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import io.flutter.plugin.common.MethodChannel
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Manages step counting logic and database operations.
 *
 * Phase 1 robustness (anti-spike) design. The TYPE_STEP_COUNTER sensor reports cumulative steps
 * since boot, and several real-world conditions used to turn that into phantom single-entry spikes
 * of 1k to 50k steps. This class now defends against them at the point of ingestion:
 *
 *  1. Garbage gate: non-finite, negative, or absurd (> 1e9) sensor values are dropped before any
 *     arithmetic, so a hub glitch (uint32-as-float, Float.MAX_VALUE, Infinity) can never be booked.
 *  2. Boot-session anchoring: the baseline is tagged with BOOT_COUNT + ANDROID_ID. If it was saved
 *     under a different boot session or device (missed reboot, Auto Backup restore, reinstall), it
 *     is radioactive and we re-anchor with zero credit instead of booking the arbitrary difference.
 *  3. Rate gate: a delta faster than a human can physically walk over the elapsed time since the
 *     last event is quarantined (anchor frozen, nothing credited), not capped-and-added.
 *
 * Honest Phase 1 limits (addressed in later phases): steps are still stamped at flush time rather
 * than at their true event time (time attribution is Phase 3), and the pending-buffer durability
 * protocol (exactly-once anchor-in-transaction) is Phase 2. See docs/robust_step_counting_spec.md.
 */
class StepCountManager(context: Context, private val onFlushSuccess: () -> Unit = {}) {
    companion object {
        private const val TAG = "StepCountManager"
        private const val PREFS_NAME = "steps_count_prefs"
        private const val KEY_LAST_SENSOR_VALUE = "last_sensor_value"
        private const val KEY_IS_INITIALIZED = "is_initialized"
        private const val KEY_PENDING_STEPS = "pending_steps"
        // Phase 1: baseline identity + monotonic time of the last processed event.
        private const val KEY_LAST_EVENT_ELAPSED = "last_event_elapsed"
        private const val KEY_BOOT_ID = "boot_id"

        // Flush thresholds
        private const val FLUSH_STEP_THRESHOLD = 50
        private const val FLUSH_INTERVAL_MS = 60_000L // 60 seconds

        /**
         * Garbage gate (EC-1/EC-48): reject non-finite/negative/absurd cumulative values BEFORE
         * computing a delta. 1e9 is ~4 steps/sec for 8 years of continuous uptime, comfortably above
         * any real lifetime count while still rejecting uint32-as-float (4.29e9), Float.MAX_VALUE and
         * +Infinity. This replaces the old "cap the delta to 500k and add it as real steps" logic,
         * which was itself a primary spike source.
         */
        private const val GARBAGE_ABS_MAX = 1.0e9f

        /**
         * Rate gate (EC-3): the physically-plausible step ceiling for an elapsed interval. Burst
         * ceiling of 5 steps/sec (above world-record cadence, ~2x a hard run) for the first hour,
         * then 1.2 steps/sec sustained, plus a small constant slack. Replaces the static
         * MAX_REASONABLE_DELTA = 500_000, which was ~1000x too loose to catch anything real.
         */
        private const val RATE_BURST_PER_SEC = 5.0
        private const val RATE_SUSTAINED_PER_SEC = 1.2
        private const val RATE_BASE_SLACK = 60L
        private const val RATE_BURST_WINDOW_SEC = 3600L

        /**
         * Quarantine (EC-1/EC-3): when a delta is implausible we freeze the anchor and credit nothing.
         * If the high value PERSISTS for this many mutually-consistent events across at least this much
         * time, it is treated as a genuine (rare) hardware re-baseline and we re-anchor to it with zero
         * credit. A transient glitch never reaches confirmation, so it costs nothing.
         */
        private const val QUARANTINE_CONFIRM_COUNT = 3
        private const val QUARANTINE_CONFIRM_MS = 600_000L // 10 minutes

        /**
         * Cap per DB row for hygiene; excess is deferred to next flush (no step loss). Real deltas are
         * now bounded by the rate gate, so this is only reached by long legitimate catch-ups.
         */
        private const val MAX_STEPS_PER_ROW = 50_000

        /** Delta above this is logged as batch_detected (OEM batching evidence; logs only, not DB). */
        private const val BATCH_DETECTION_THRESHOLD = FLUSH_STEP_THRESHOLD * 5

        /** Cap in-memory buffer to avoid Integer overflow when summing many large deltas. */
        private const val MAX_PENDING_STEPS = 1_000_000

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
    }

    private val database = StepCountDatabase(context)
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    // H2: SupervisorJob ensures a failing child coroutine does not cancel the flush loop
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Boot-session identity for THIS process (constant for the process lifetime: a reboot kills the
    // process). Computed once to avoid a content-provider query on every sensor event.
    private val thisBootId: String = computeBootId(context.applicationContext)

    // Baseline state: always read/written on the sensor callback thread only (C1).
    private var lastSensorValue: Float = 0f
    private var isInitialized = false
    // Boot id under which lastSensorValue was recorded, and the monotonic (elapsedRealtime) time of the
    // last processed event. Both persist across process death so the rate gate has a real dt on restart.
    private var lastBootId: String = ""
    private var lastEventElapsedMs: Long = -1L

    // Quarantine state for implausible deltas (EC-3). The anchor is frozen while active.
    private var quarantineActive = false
    private var quarantineCount = 0
    private var quarantineValue = 0f
    private var quarantineFirstElapsedMs = 0L
    private var quarantineLastElapsedMs = 0L

    // In-memory accumulator: steps not yet written to SQLite.
    // AtomicInteger ensures thread-safe read-modify-write between the
    // sensor callback thread and flush coroutines.
    private val pendingSteps = AtomicInteger(0)

    // Periodic flush job
    private var flushJob: Job? = null

    init {
        loadState()
        startPeriodicFlush()
    }

    /**
     * Load saved state from SharedPreferences
     */
    private fun loadState() {
        lastSensorValue = prefs.getFloat(KEY_LAST_SENSOR_VALUE, 0f)
        isInitialized = prefs.getBoolean(KEY_IS_INITIALIZED, false)
        // Recover steps buffered but not yet flushed to DB before last process death
        pendingSteps.set(prefs.getInt(KEY_PENDING_STEPS, 0))
        lastEventElapsedMs = prefs.getLong(KEY_LAST_EVENT_ELAPSED, -1L)
        lastBootId = prefs.getString(KEY_BOOT_ID, "") ?: ""

        Log.d(
            TAG, "State loaded - lastSensorValue: $lastSensorValue, isInitialized: $isInitialized, " +
                 "pendingSteps: ${pendingSteps.get()}, lastBootId: $lastBootId, thisBootId: $thisBootId"
        )
    }

    /**
     * Persist baseline + identity + pending buffer to SharedPreferences in a single edit.
     * Called only from the sensor callback thread (never from a coroutine).
     *
     * The baseline (lastSensorValue) advances IMMEDIATELY in onSensorChanged(), before any DB write.
     * Persisting pendingSteps here too (not only after a flush) shrinks the crash-loss window for
     * buffered steps. The full exactly-once durability protocol (anchor-in-transaction) is Phase 2.
     */
    private fun saveBaseline() {
        prefs.edit().apply {
            putFloat(KEY_LAST_SENSOR_VALUE, lastSensorValue)
            putBoolean(KEY_IS_INITIALIZED, isInitialized)
            putLong(KEY_LAST_EVENT_ELAPSED, lastEventElapsedMs)
            putString(KEY_BOOT_ID, lastBootId)
            putInt(KEY_PENDING_STEPS, pendingSteps.get())
            apply()
        }
    }

    /** Persist just the pending buffer (used by the flush path after a confirmed DB write). */
    private fun persistPending() {
        prefs.edit().putInt(KEY_PENDING_STEPS, pendingSteps.get()).apply()
    }

    /**
     * Boot-session fingerprint: BOOT_COUNT (changes on every reboot, API 24+) plus ANDROID_ID
     * (changes across devices / reinstall on a different device). A baseline whose stored fingerprint
     * differs from the current one cannot be trusted for a delta.
     */
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

    /**
     * Re-establish the baseline at [sensorValue] with ZERO credit for anything before it, flushing any
     * already-buffered (real) steps first so they are not lost. Used on first init, on a boot/device
     * change, and on a confirmed quarantine. Never books the difference between the old and new
     * baseline: that difference is exactly the phantom-spike vector.
     */
    private fun reAnchor(sensorValue: Float, elapsedMs: Long, bootId: String) {
        lastSensorValue = sensorValue
        lastEventElapsedMs = elapsedMs
        lastBootId = bootId
        isInitialized = true
        clearQuarantine()
        // Persist the new baseline BEFORE launching the flush, so the flush's post-drain
        // persistPending(0) cannot be overwritten by this save re-writing the old pending value.
        saveBaseline()
        coroutineScope.launch { flushPendingSteps() } // book any already-buffered (real) steps
        stepCountChannel?.invokeMethod("onSensorChanged", null)
    }

    /**
     * Process new sensor data and update step count.
     * Steps are accumulated in-memory; DB writes happen in batches.
     *
     * @param sensorValue The raw cumulative value from the TYPE_STEP_COUNTER sensor (steps since boot).
     * @param eventTimestampNanos The hardware event timestamp (nanoseconds on the elapsedRealtime clock).
     *   Used only to rate-gate the delta by real elapsed time; 0 (unknown) falls back to "now".
     */
    fun onSensorChanged(sensorValue: Float, eventTimestampNanos: Long = 0L) {
        try {
            // (1) Garbage gate (EC-1/EC-48): drop unusable values before any arithmetic so a hub
            // glitch can neither be credited nor corrupt the baseline.
            if (!isAcceptableSensorValue(sensorValue)) {
                Log.w(TAG, "garbage_value raw=$sensorValue rejected (baseline untouched)")
                return
            }

            val nowElapsed = SystemClock.elapsedRealtime()
            val evElapsed = eventTimestampNanos / 1_000_000
            // Guard against bogus HAL timestamps (future-dated or epoch-based): fall back to now.
            val elapsedMs = if (evElapsed in 1..(nowElapsed + 10_000)) evElapsed else nowElapsed

            // (2) First-ever initialization: anchor with zero credit.
            if (!isInitialized) {
                reAnchor(sensorValue, elapsedMs, thisBootId)
                Log.d(TAG, "Initialized with sensor value: $sensorValue (boot=$thisBootId)")
                return
            }

            // (3) Boot-session anchoring (EC-2/EC-11). A baseline recorded under a different boot
            // count or device (missed reboot, Auto Backup restore, reinstall-on-new-device) is
            // radioactive: re-anchor with zero credit instead of booking the arbitrary difference.
            if (lastBootId.isEmpty()) {
                // Upgrade from a pre-boot-tracking build: adopt the current fingerprint and keep the
                // baseline. Any stale-baseline delta is now bounded by the rate gate, so no spike.
                lastBootId = thisBootId
                saveBaseline()
            } else if (lastBootId != thisBootId) {
                Log.w(TAG, "boot/device changed ($lastBootId -> $thisBootId): re-anchoring with zero credit")
                reAnchor(sensorValue, elapsedMs, thisBootId)
                return
            }

            // H1: compute delta in Double to avoid Float precision loss at high cumulative counts
            val stepDifference = (sensorValue.toDouble() - lastSensorValue.toDouble()).roundToInt()

            if (stepDifference == 0) {
                // Duplicate / heartbeat value (EC-51): nothing to credit.
                stepCountChannel?.invokeMethod("onSensorChanged", null)
                return
            }

            if (stepDifference < 0) {
                // Negative delta WITHOUT a boot change = in-session hardware counter reset (EC-49:
                // hub thermal/watchdog restart). Re-baseline; Phase 1 does not recover the pre-reset gap.
                Log.w(TAG, "counter_reset old=$lastSensorValue new=$sensorValue; re-baselining")
                lastSensorValue = sensorValue
                lastEventElapsedMs = elapsedMs
                clearQuarantine()
                saveBaseline()
                coroutineScope.launch { flushPendingSteps() } // book whatever was already buffered
                stepCountChannel?.invokeMethod("onSensorChanged", null)
                return
            }

            // stepDifference > 0
            if (stepDifference > BATCH_DETECTION_THRESHOLD) {
                Log.d(TAG, "batch_detected delta=$stepDifference (threshold=$BATCH_DETECTION_THRESHOLD)")
            }

            // (4) Rate gate (EC-3). dt is the monotonic elapsed time since the last processed event;
            // a delta above the physically-plausible ceiling for that window is quarantined, not booked.
            val dtSec = if (lastEventElapsedMs in 0 until elapsedMs) (elapsedMs - lastEventElapsedMs) / 1000 else 0L
            val cap = plausibleMax(dtSec)
            if (stepDifference > cap) {
                handleImplausibleDelta(sensorValue, elapsedMs, stepDifference, dtSec, cap)
                return // anchor NOT advanced, nothing credited (no cap-and-add)
            }

            // (5) Plausible, real steps. Clear any quarantine and credit them.
            clearQuarantine()
            lastSensorValue = sensorValue
            lastEventElapsedMs = elapsedMs

            // Cap running total to avoid Integer overflow (use Long math for the comparison).
            val current = pendingSteps.get()
            val addCapped = ((current.toLong() + stepDifference).coerceAtMost(MAX_PENDING_STEPS.toLong())).toInt() - current
            if (addCapped > 0) {
                pendingSteps.addAndGet(addCapped)
                Log.d(TAG, "Steps buffered: $addCapped, Pending: ${pendingSteps.get()}")
            }
            if (addCapped < stepDifference) {
                Log.w(TAG, "Pending steps capped at $MAX_PENDING_STEPS; dropped ${stepDifference - addCapped} from this event.")
            }
            saveBaseline() // persists baseline + identity + pending in one edit

            if (pendingSteps.get() >= FLUSH_STEP_THRESHOLD) {
                coroutineScope.launch { flushPendingSteps() }
            }
            stepCountChannel?.invokeMethod("onSensorChanged", null)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing sensor data: ${e.message}")
        }
    }

    /**
     * Handle a delta that exceeded the physical-rate cap. The anchor is FROZEN (baseline not advanced)
     * and nothing is credited, so a transient glitch produces zero phantom steps. If the high value
     * persists for QUARANTINE_CONFIRM_COUNT mutually-consistent events across at least
     * QUARANTINE_CONFIRM_MS, it is treated as a genuine hardware re-baseline and we re-anchor to it
     * with zero credit (the intervening steps are dropped, not fabricated, and are logged).
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
            reAnchor(sensorValue, elapsedMs, thisBootId)
        }
    }

    /**
     * Start a coroutine that flushes the pending buffer every FLUSH_INTERVAL_MS.
     */
    private fun startPeriodicFlush() {
        flushJob = coroutineScope.launch {
            while (true) {
                delay(FLUSH_INTERVAL_MS)
                flushPendingSteps()
            }
        }
    }

    /**
     * Write all buffered steps to SQLite atomically.
     * M3: pendingSteps is written to SharedPreferences only after a successful DB insert.
     * M4: private; external callers must not drive flushes directly.
     */
    private suspend fun flushPendingSteps() {
        // Atomically snapshot and zero the accumulator.
        // Any steps added by the sensor thread AFTER this point go into the next flush.
        val stepsToFlush = pendingSteps.getAndSet(0)
        if (stepsToFlush <= 0) return

        // Cap per row only to avoid overflow/absurd values (e.g. Integer.MAX_VALUE). OEM batching
        // can legitimately produce large single-row entries; we don't cap those away.
        val toInsert = stepsToFlush.coerceAtMost(MAX_STEPS_PER_ROW)
        if (toInsert < stepsToFlush) {
            pendingSteps.addAndGet(stepsToFlush - toInsert)
            Log.w(
                TAG,
                "Flush capped to $MAX_STEPS_PER_ROW; ${stepsToFlush - toInsert} steps deferred to next flush."
            )
        }

        val inserted: Boolean = try {
            val utcTimestamp = TimeStampUtils.getCurrentUtcTimestamp()
            val uuid = database.insertStepCount(toInsert, utcTimestamp)
            if (uuid == null) {
                // EC-4: insert failed. insertStepCount returns null on BOTH a -1 rowId and a swallowed
                // exception, so a null here is the only reliable failure signal. Restore the steps and
                // do NOT report success, instead of the old code's silent loss + false "flushed" log.
                pendingSteps.addAndGet(toInsert)
                Log.e(TAG, "Flush failed (insert returned null); restored $toInsert to buffer")
                false
            } else {
                // M3: persist the decremented buffer only after a confirmed DB write.
                persistPending()
                Log.d(TAG, "Flushed $toInsert steps to DB at $utcTimestamp (UTC). Remaining: ${pendingSteps.get()}")
                true
            }
        } catch (e: Exception) {
            // EC-14: restore ONLY toInsert. The deferred remainder was already re-added above; the
            // original code added it a second time here, which double-counted it on the next flush.
            pendingSteps.addAndGet(toInsert)
            Log.e(TAG, "Failed to flush steps to DB; restored $toInsert to buffer: ${e.message}")
            false
        }

        // M1: notify the caller (BackgroundServiceManager) to refresh the notification. EC-14: this is
        // OUTSIDE the accounting try. If updateNotification() throws (OEM DeadSystemException under
        // memory pressure), it must not trigger the catch above and re-flush already-persisted steps.
        if (inserted) {
            try {
                onFlushSuccess()
            } catch (e: Exception) {
                Log.e(TAG, "onFlushSuccess (notification refresh) failed: ${e.message}")
            }
        }
    }

    /**
     * Run a full WAL checkpoint on the same DB instance used for step data.
     * Call before copying the database file for export so the copy is consistent.
     *
     * @return true if checkpoint completed successfully, false otherwise
     */
    fun runWalCheckpointForExport(): Boolean = database.runWalCheckpointFull()

    /**
     * Get total step count for a date range
     * @param startDate Start date in milliseconds (nullable - if null, no start limit)
     * @param endDate End date in milliseconds (nullable - if null, no end limit)
     * @return Total steps in the specified range (includes pending buffer when range includes now)
     */
    fun getStepCount(startDate: Long? = null, endDate: Long? = null): Int {
        return try {
            Log.d(TAG, "Filter Local Start TimeStamp: $startDate")
            Log.d(TAG, "Filter Local End TimeStamp: $endDate")
            var startUTCTimestamp: Long? = null
            if (startDate != null) {
                startUTCTimestamp = TimeStampUtils.convertLocalTimestampToUtc(startDate)
            }
            var endUTCTimestamp: Long? = null
            if (endDate != null) {
                endUTCTimestamp = TimeStampUtils.convertLocalTimestampToUtc(endDate)
            }
            Log.d(TAG, "Filter UTC Start TimeStamp: $startUTCTimestamp")
            Log.d(TAG, "Filter UTC End TimeStamp: $endUTCTimestamp")

            val dbSteps = database.getStepCount(startUTCTimestamp, endUTCTimestamp)
            val nowUtc = TimeStampUtils.getCurrentUtcTimestamp()
            val rangeIncludesNow = (startUTCTimestamp == null || startUTCTimestamp <= nowUtc) &&
                (endUTCTimestamp == null || endUTCTimestamp >= nowUtc)
            val total = dbSteps + if (rangeIncludesNow) pendingSteps.get() else 0
            Log.d(TAG, "Step count query - DB: $dbSteps, pending: ${pendingSteps.get()}, total: $total")
            total
        } catch (e: Exception) {
            Log.e(TAG, "Error getting step count: ${e.message}")
            0
        }
    }

    /**
     * Get today's step count from database plus any steps not yet flushed (pending buffer).
     * Read-only; does not flush the buffer.
     *
     * @return Total steps for the current day (00:00 - 23:59 local) including pending
     */
    fun getTodaysCount(): Int {
        return try {
            val startLocalTimestamp = TimeStampUtils.getTodaysTimestamp(true)
            val endLocalTimestamp = TimeStampUtils.getTodaysTimestamp(false)

            Log.d(TAG, "Todays Local Start TimeStamp: $startLocalTimestamp")
            Log.d(TAG, "Todays Local End TimeStamp: $endLocalTimestamp")

            val startUTCTimestamp = TimeStampUtils.convertLocalTimestampToUtc(startLocalTimestamp)
            val endUTCTimestamp = TimeStampUtils.convertLocalTimestampToUtc(endLocalTimestamp)

            Log.d(TAG, "Todays UTC Start TimeStamp: $startUTCTimestamp")
            Log.d(TAG, "Todays UTC End TimeStamp: $endUTCTimestamp")

            val dbSteps = database.getStepCount(startUTCTimestamp, endUTCTimestamp)
            val pending = pendingSteps.get()
            val total = dbSteps + pending
            Log.d(TAG, "Today's step count - DB: $dbSteps, pending: $pending, total: $total")
            total
        } catch (e: Exception) {
            Log.e(TAG, "Error getting today's step count: ${e.message}")
            0 // Return 0 if DB query fails
        }
    }

    /**
     * Get timeline data - list of step entries with timestamps
     * @param startDate Start date in milliseconds (nullable - if null, no start limit)
     * @param endDate End date in milliseconds (nullable - if null, no end limit)
     * @param timeZone The timezone type for returned timestamps. Default is LOCAL.
     * @return List of maps containing step_count and timestamp
     */
    fun getTimeline(
        startDate: Long? = null, endDate: Long? = null, timeZone: TimeZoneType = TimeZoneType.LOCAL
    ): List<Map<String, Any>> {
        return try {
            Log.d(TAG, "Timeline Filter Local Start TimeStamp: $startDate")
            Log.d(TAG, "Timeline Filter Local End TimeStamp: $endDate")
            Log.d(TAG, "Timeline Return TimeZone Type: $timeZone")
            
            // Convert input timestamps to UTC for database query (input timestamps are always treated as local time)
            var startUTCTimestamp: Long? = null
            if (startDate != null) {
                startUTCTimestamp = TimeStampUtils.convertLocalTimestampToUtc(startDate)
            }
            
            var endUTCTimestamp: Long? = null
            if (endDate != null) {
                endUTCTimestamp = TimeStampUtils.convertLocalTimestampToUtc(endDate)
            }

            Log.d(TAG, "Timeline Filter UTC Start TimeStamp: $startUTCTimestamp")
            Log.d(TAG, "Timeline Filter UTC End TimeStamp: $endUTCTimestamp")

            // Get timeline data from database (stored in UTC)
            val dbTimelineData = database.getTimelineData(startUTCTimestamp, endUTCTimestamp)

            // Convert timestamps in response based on requested format
            val responseData = dbTimelineData.map { entry ->
                val utcTimestamp = entry["timestamp"] as Long
                val stepCount = entry["step_count"] as Int
                val uuid = entry["uuid"] as? String
                val responseTimestamp = if (timeZone.isLocal) {
                    TimeStampUtils.convertUtcTimestampToLocal(utcTimestamp)
                } else {
                    utcTimestamp // Keep as UTC
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
     * Get all timeline entries recorded strictly after [lastSyncTimestamp] (UTC ms).
     * If [lastSyncTimestamp] is null, the entire timeline is returned.
     *
     * @param lastSyncTimestamp Last-synced UTC timestamp in milliseconds, or null for all data
     * @return List of timeline maps ordered by timestamp ASC, with timestamps in UTC
     */
    fun getTimelineAfter(lastSyncTimestamp: Long?): List<Map<String, Any>> {
        return try {
            Log.d(TAG, "getTimelineAfter - lastSyncTimestamp (UTC): $lastSyncTimestamp")

            val dbData = if (lastSyncTimestamp != null) {
                database.getTimelineDataAfter(lastSyncTimestamp)
            } else {
                database.getTimelineData() // no filter: return everything
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
     * Clean up resources. Guarantees the periodic flush is fully stopped and
     * all buffered steps are persisted before the database is closed.
     *
     * Uses runBlocking so cleanup is sequential even on OEM kill paths where
     * onDestroy() has no coroutine scope available.
     *
     * Order: cancelAndJoin (stop periodic flush) → flushPendingSteps (final flush) → close DB
     * This ensures no flush is ever in-flight when the DB is closed.
     */
    fun cleanup() {
        runBlocking {
            flushJob?.cancelAndJoin()  // wait for any in-flight periodic flush to finish
            flushPendingSteps()         // final drain of the buffer
        }
        database.close()               // safe: no coroutine is touching the DB anymore
    }
}
