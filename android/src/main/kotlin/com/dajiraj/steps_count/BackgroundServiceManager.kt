package com.dajiraj.steps_count

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class BackgroundServiceManager : Service(), SensorEventListener {
    companion object {
        private const val TAG = "BackgroundServiceManager"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "steps_count_channel"
        private const val CHANNEL_NAME = "Steps Count Service"

        /** Hardware FIFO batching window (5 min) so the SoC is not woken for every step (EC-8). */
        private const val MAX_REPORT_LATENCY_US = 5 * 60 * 1_000_000

        // Service state
        private var isRunning = false
        private var serviceInstance: BackgroundServiceManager? = null

        // Single shared instance – null when the service is not running
        @Volatile
        var stepCountManager: StepCountManager? = null
            private set

        // Public methods
        fun isServiceRunning(): Boolean = isRunning

        fun stopBackgroundService(context: Context) {
            val intent = Intent(context, BackgroundServiceManager::class.java).apply {
                action = "FORCE_STOP"
            }
            context.startService(intent)
        }
    }

    private lateinit var sensorManager: SensorManager
    private var stepCounterSensor: Sensor? = null
    private lateinit var serviceScope: CoroutineScope

    // H5: guard to prevent double-cleanup on interleaved stopService() + onDestroy() calls
    private var cleanupDone = false

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
        createNotificationChannel()
        initializeSensors()
        initializeStepManager()
        serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_SERVICE" -> {
                startService()
            }

            "FORCE_STOP" -> {
                stopService()
            }

            else -> {
                // Default action - start the service
                startService()
            }
        }

        // START_REDELIVER_INTENT ensures the service is restarted with the last intent if killed
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background service for counting steps"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun initializeSensors() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        // Prefer the wake-up step counter so the SoC is woken to deliver batches (EC-8); fall back to
        // the default (non-wake-up) variant, whose FIFO still preserves per-event timestamps.
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        Log.d(TAG, "Step counter sensor available: ${stepCounterSensor != null} (wakeup=${stepCounterSensor?.isWakeUpSensor})")
    }

    private fun initializeStepManager() {
        try {
            // H3: write directly to companion; no redundant local field
            Companion.stepCountManager = StepCountManager(
                context = this.applicationContext,
                onFlushSuccess = { updateNotification() }  // M1: notification on each flush
            )
            Log.d(TAG, "Step count manager initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize step count manager: ${e.message}")
        }
    }

    private fun startService() {
        if (isRunning) return
        isRunning = true

        registerSensor()
        startForegroundService()
        updateNotification() // initial notification with current step count

        Log.d(TAG, "Service started successfully")
    }

    private fun stopService() {
        doCleanup()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun registerSensor() {
        stepCounterSensor?.let { sensor ->
            // maxReportLatencyUs enables hardware FIFO batching: while the SoC sleeps, the sensor hub
            // buffers events (each keeping its true timestamp) and delivers them on wake, which is both
            // battery-friendly and lets the manager attribute steps to when they happened (EC-8).
            val success = sensorManager.registerListener(
                this, sensor, SensorManager.SENSOR_DELAY_NORMAL, MAX_REPORT_LATENCY_US
            )
            if (success) {
                Log.d(
                    TAG, "Step counter sensor registered"
                )
            } else {
                Log.w(
                    TAG, "Failed to register step counter sensor"
                )
            }
        }
    }

    private fun unregisterSensor() {
        sensorManager.unregisterListener(this)
        Log.d(TAG, "Sensor unregistered")
    }

    private fun startForegroundService() {
        try {
            val notification = createInitialNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "Foreground service started in onCreate")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}", e)
            // If we can't start foreground, stop the service
            stopSelf()
            return
        }
    }

    private fun createInitialNotification(): Notification {
        // Create a simple notification for initial foreground service start
        // This is called in onCreate() before stepCountManager is initialized
        // Create intent to open the main Flutter activity
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = if (intent != null) {
            PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            null
        }

        // H4: companion property is nullable; use null-safe call instead of stale lateinit guard
        val todaysSteps = stepCountManager?.getTodaysCount() ?: 0

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Steps Count")
            .setContentText("Today's Steps: $todaysSteps")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .apply {
                pendingIntent?.let { setContentIntent(it) }
            }
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }


    // H5: centralised, guarded cleanup. Safe to call from both stopService() and onDestroy().
    private fun doCleanup() {
        if (cleanupDone) return
        cleanupDone = true
        isRunning = false
        serviceInstance = null
        unregisterSensor()
        stepCountManager?.cleanup()
        Companion.stepCountManager = null
    }

    private fun updateNotification() {
        val notification = createInitialNotification()
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(
            NOTIFICATION_ID, notification
        )
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let { sensorEvent ->
            // H4: companion property is nullable; check != null instead of stale lateinit guard
            val manager = stepCountManager
            if (sensorEvent.sensor.type == Sensor.TYPE_STEP_COUNTER && manager != null) {
                val sensorValue = sensorEvent.values[0]
                // Forward the hardware event timestamp (nanoseconds since boot, valid even for
                // FIFO-batched events). The manager needs it to rate-gate deltas by real elapsed time.
                manager.onSensorChanged(sensorValue, sensorEvent.timestamp)
                Log.d(TAG, "Step sensor value: $sensorValue")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Handle accuracy changes if needed
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        doCleanup()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}