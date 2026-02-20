package com.dajiraj.steps_count

import android.content.Context
import android.content.SharedPreferences
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
 * Manages step counting logic and database operations
 */
class StepCountManager(context: Context, private val onFlushSuccess: () -> Unit = {}) {
    companion object {
        private const val TAG = "StepCountManager"
        private const val PREFS_NAME = "steps_count_prefs"
        private const val KEY_LAST_SENSOR_VALUE = "last_sensor_value"
        private const val KEY_IS_INITIALIZED = "is_initialized"
        private const val KEY_PENDING_STEPS = "pending_steps"

        // Flush thresholds
        private const val FLUSH_STEP_THRESHOLD = 50
        private const val FLUSH_INTERVAL_MS = 60_000L // 60 seconds

        var stepCountChannel: MethodChannel? = null
    }

    private val database = StepCountDatabase(context)
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    // H2: SupervisorJob ensures a failing child coroutine does not cancel the flush loop
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Baseline state — always read/written on the sensor callback thread only (C1)
    private var lastSensorValue: Float = 0f
    private var isInitialized = false

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

        Log.d(
            TAG, "State loaded - lastSensorValue: $lastSensorValue, " +
                 "isInitialized: $isInitialized, pendingSteps: ${pendingSteps.get()}"
        )
    }

    /**
     * Persist the sensor baseline to SharedPreferences on every sensor event.
     * Called only from the sensor callback thread (never from a coroutine).
     *
     * IMPORTANT: The baseline (lastSensorValue) advances IMMEDIATELY in onSensorChanged(),
     * before any DB write. Crash safety for buffered steps comes from pendingSteps being
     * written after each successful DB flush in flushPendingSteps() — NOT from baseline
     * immutability. Do not delay the baseline advance; doing so reintroduces step loss on reset.
     */
    private fun saveBaseline() {
        prefs.edit().apply {
            putFloat(KEY_LAST_SENSOR_VALUE, lastSensorValue)
            putBoolean(KEY_IS_INITIALIZED, isInitialized)
            apply()
        }
    }

    /**
     * Process new sensor data and update step count.
     * Steps are accumulated in-memory; DB writes happen in batches.
     * @param sensorValue The raw value from TYPE_STEP_COUNTER sensor
     */
    fun onSensorChanged(sensorValue: Float) {
        try {
            if (!isInitialized) {
                lastSensorValue = sensorValue
                isInitialized = true
                saveBaseline()
                Log.d(TAG, "Initialized with sensor value: $sensorValue")
                return
            }

            // H1: compute delta in Double to avoid Float precision loss at high cumulative counts
            val stepDifference = (sensorValue.toDouble() - lastSensorValue.toDouble()).roundToInt()

            if (stepDifference > 0) {
                // Advance baseline on the sensor callback thread, then buffer the delta
                lastSensorValue = sensorValue
                pendingSteps.addAndGet(stepDifference)

                Log.d(TAG, "Steps buffered: $stepDifference, Pending: ${pendingSteps.get()}")

                // M3: only persist baseline here; pendingSteps is written after each successful flush
                saveBaseline()

                if (pendingSteps.get() >= FLUSH_STEP_THRESHOLD) {
                    coroutineScope.launch { flushPendingSteps() }
                }
            } else if (stepDifference < 0) {
                // C1: Sensor reset — re-baseline synchronously on the sensor callback thread
                // to avoid cross-thread writes to lastSensorValue.
                // Only the DB flush is dispatched to IO.
                Log.w(TAG, "Sensor reset detected. Re-baselining synchronously.")
                lastSensorValue = sensorValue
                saveBaseline()
                coroutineScope.launch { flushPendingSteps() }
            }
            stepCountChannel?.invokeMethod("onSensorChanged", null)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing sensor data: ${e.message}")
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
     * M4: private — external callers must not drive flushes directly.
     */
    private suspend fun flushPendingSteps() {
        // Atomically snapshot and zero the accumulator.
        // Any steps added by the sensor thread AFTER this point go into the next flush.
        val stepsToFlush = pendingSteps.getAndSet(0)
        if (stepsToFlush <= 0) return

        try {
            val utcTimestamp = TimeStampUtils.getCurrentUtcTimestamp()
            database.insertStepCount(stepsToFlush, utcTimestamp)

            // M3: write pendingSteps to prefs only after confirmed DB success
            prefs.edit().putInt(KEY_PENDING_STEPS, pendingSteps.get()).apply()

            Log.d(TAG, "Flushed $stepsToFlush steps to DB at $utcTimestamp (UTC). Remaining: ${pendingSteps.get()}")

            // M1: notify caller (BackgroundServiceManager) to refresh the notification
            onFlushSuccess()
        } catch (e: Exception) {
            // DB write failed: restore snapshot so steps are never silently lost
            pendingSteps.addAndGet(stepsToFlush)
            Log.e(TAG, "Failed to flush steps to DB – restored $stepsToFlush to buffer: ${e.message}")
        }
    }

    /**
     * Get total step count for a date range
     * @param startDate Start date in milliseconds (nullable - if null, no start limit)
     * @param endDate End date in milliseconds (nullable - if null, no end limit)
     * @return Total steps in the specified range
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
            // M2: include buffered-but-not-yet-flushed steps for an up-to-date total
            val total = dbSteps + pendingSteps.get()
            Log.d(TAG, "Step count query - DB: $dbSteps, Pending: ${pendingSteps.get()}, Total: $total")
            total
        } catch (e: Exception) {
            Log.e(TAG, "Error getting step count: ${e.message}")
            0
        }
    }

    /**
     * Get today's step count from database
     * @return Total steps for the current day (00:00 - 23:59 UTC)
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

            // Get steps from database for today's range
            val dbSteps = database.getStepCount(startUTCTimestamp, endUTCTimestamp)
            // M2: include buffered-but-not-yet-flushed steps so UI reflects real-time count
            val total = dbSteps + pendingSteps.get()
            Log.d(TAG, "Today's step count - DB: $dbSteps, Pending: ${pendingSteps.get()}, Total: $total")
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
