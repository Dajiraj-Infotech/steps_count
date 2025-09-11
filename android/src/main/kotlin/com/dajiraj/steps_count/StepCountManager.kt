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

        var stepCountChannel: MethodChannel? = null
    }

    private val database = StepCountDatabase(context)
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    // Track step counting state
    private var lastSensorValue: Float = 0f
    private var sessionSteps: Int = 0
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

        Log.d(
            TAG, "State loaded - lastSensorValue: $lastSensorValue, sessionSteps: $sessionSteps"
        )
    }

    /**
     * Save current state to SharedPreferences
     */
    private fun saveState() {
        prefs.edit().apply {
            putFloat(KEY_LAST_SENSOR_VALUE, lastSensorValue)
            putInt(KEY_SESSION_STEPS, sessionSteps)
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
                lastSensorValue = sensorValue

                Log.d(
                    TAG, "Steps detected: $stepDifference, Total session: $sessionSteps"
                )

                // Save to database immediately
                saveStepsToDatabase(stepDifference)

                // Save state
                saveState()
            } else if (stepDifference < 0) {
                // Handle sensor reset (device reboot, etc.)
                Log.w(TAG, "Sensor reset detected. Reinitializing.")
                lastSensorValue = sensorValue
                saveState()
            }
            stepCountChannel?.invokeMethod("onSensorChanged", null)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing sensor data: ${e.message}")
        }
    }

    /**
     * Save steps to database immediately
     */
    private fun saveStepsToDatabase(steps: Int) {
        if (steps <= 0) return

        coroutineScope.launch {
            try {
                val utcTimestamp = TimeStampUtils.getCurrentUtcTimestamp()
                database.insertStepCount(steps, utcTimestamp)

                val utcTimestampFormated = TimeStampUtils.formatUtcTimestamp(utcTimestamp)
                Log.d(
                    TAG, "Saved $steps steps to database at $utcTimestampFormated (UTC)"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save steps to database: ${e.message}")
            }
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
            val currentUtcTime = TimeStampUtils.getCurrentUtcTimestamp()
            val includeSessionSteps = when {
                startDate != null && currentUtcTime < startDate -> false
                endDate != null && currentUtcTime > endDate -> false
                else -> true
            }

            val totalSteps = dbSteps

            Log.d(
                TAG, "Step count query - DB: $dbSteps, Total: $totalSteps"
            )
            totalSteps
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
            val startTimestamp = TimeStampUtils.getTodaysStartTimestamp()
            val endTimestamp = TimeStampUtils.getTodaysEndTimestamp()

            // Get steps from database for today's range
            val dbSteps = database.getStepCount(startTimestamp, endTimestamp)

            Log.d(TAG, "Today's step count - DB: $dbSteps, Total: $dbSteps")
            dbSteps
        } catch (e: Exception) {
            Log.e(TAG, "Error getting today's step count: ${e.message}")
            0 // Return 0 if DB query fails
        }
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        database.close()
    }
}
