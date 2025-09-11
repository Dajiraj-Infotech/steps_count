package com.dajiraj.steps_count

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import io.flutter.plugin.common.MethodChannel
import java.util.*

/**
 * Manages step counting logic and database operations
 */
class StepCountManager(context: Context) {
    companion object {
        private const val TAG = "StepCountManager"
        private const val PREFS_NAME = "steps_count_prefs"
        private const val KEY_LAST_SENSOR_VALUE = "last_sensor_value"
        private const val KEY_SESSION_STEPS = "session_steps"
        private const val KEY_PENDING_STEPS = "pending_steps"
        private const val STEPS_BATCH_SIZE = 10

        var stepCountChannel: MethodChannel? = null
    }

    private val database = StepCountDatabase(context)
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    // Track step counting state
    private var lastSensorValue: Float = 0f
    private var sessionSteps: Int = 0
    private var pendingSteps: Int = 0
    private var isInitialized = false

    init {
        loadState()
    }

    /**
     * Load saved state from SharedPreferences
     */
    private fun loadState() {
        lastSensorValue = prefs.getFloat(KEY_LAST_SENSOR_VALUE, 0f)
        sessionSteps = prefs.getInt(KEY_SESSION_STEPS, 0)
        pendingSteps = prefs.getInt(KEY_PENDING_STEPS, 0)

        Log.d(
            TAG,
            "State loaded - lastSensorValue: $lastSensorValue, sessionSteps: $sessionSteps, pendingSteps: $pendingSteps"
        )
    }

    /**
     * Save current state to SharedPreferences
     */
    private fun saveState() {
        prefs.edit().apply {
            putFloat(KEY_LAST_SENSOR_VALUE, lastSensorValue)
            putInt(KEY_SESSION_STEPS, sessionSteps)
            putInt(KEY_PENDING_STEPS, pendingSteps)
            apply()
        }
    }

    /**
     * Process new sensor data and update step count
     * @param sensorValue The raw value from TYPE_STEP_COUNTER sensor
     */
    fun onSensorChanged(sensorValue: Float) {
        try {
            if (!isInitialized) {
                // First sensor reading - initialize baseline
                lastSensorValue = sensorValue
                isInitialized = true
                Log.d(TAG, "Initialized with sensor value: $sensorValue")
                return
            }

            // Calculate step difference
            val stepDifference = (sensorValue - lastSensorValue).toInt()

            if (stepDifference > 0) {
                // Valid step increment
                sessionSteps += stepDifference
                pendingSteps += stepDifference
                lastSensorValue = sensorValue

                Log.d(
                    TAG,
                    "Steps detected: $stepDifference, Total session: $sessionSteps, Pending: $pendingSteps"
                )

                // Save to database if we have enough pending steps
                if (pendingSteps >= STEPS_BATCH_SIZE) {
                    savePendingSteps()
                }

                // Save state
                saveState()
            } else if (stepDifference < 0) {
                // Handle sensor reset (device reboot, etc.)
                Log.w(TAG, "Sensor reset detected. Saving pending steps and reinitializing.")
                if (pendingSteps > 0) {
                    savePendingSteps()
                }
                lastSensorValue = sensorValue
                saveState()
            }
            stepCountChannel?.invokeMethod("onSensorChanged", null)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing sensor data: ${e.message}")
        }
    }

    /**
     * Save pending steps to database and reset counter
     */
    private fun savePendingSteps() {
        if (pendingSteps <= 0) return

        coroutineScope.launch {
            try {
                val currentTime = System.currentTimeMillis()
                database.insertStepCount(pendingSteps, currentTime)

                Log.d(TAG, "Saved $pendingSteps steps to database at ${Date(currentTime)}")

                // Reset pending steps counter
                pendingSteps = 0
                saveState()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save steps to database: ${e.message}")
            }
        }
    }

    /**
     * Force save any pending steps (called when service stops)
     */
    fun flushPendingSteps() {
        if (pendingSteps > 0) {
            Log.d(TAG, "Flushing $pendingSteps pending steps")
            savePendingSteps()
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
            val dbSteps = database.getStepCount(startDate, endDate)

            // Add current session steps if no date filter or current time is within range
            val currentTime = System.currentTimeMillis()
            val includeSessionSteps = when {
                startDate != null && currentTime < startDate -> false
                endDate != null && currentTime > endDate -> false
                else -> true
            }

            val totalSteps = if (includeSessionSteps) {
                dbSteps + pendingSteps
            } else {
                dbSteps
            }

            Log.d(
                TAG, "Step count query - DB: $dbSteps, Pending: $pendingSteps, Total: $totalSteps"
            )
            totalSteps
        } catch (e: Exception) {
            Log.e(TAG, "Error getting step count: ${e.message}")
            0
        }
    }

    /**
     * Get current session steps (not yet saved to database)
     */
    fun getCurrentSessionSteps(): Int = sessionSteps

    /**
     * Clean up resources
     */
    fun cleanup() {
        flushPendingSteps()
        database.close()
    }
}
