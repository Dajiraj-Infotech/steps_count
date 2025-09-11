package com.dajiraj.steps_count

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class BackgroundServiceManager : Service(), SensorEventListener {
    companion object {
        private const val TAG = "BackgroundServiceManager"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "steps_count_channel"
        private const val CHANNEL_NAME = "Steps Count Service"

        // Service state
        private var isRunning = false
        private var serviceInstance: BackgroundServiceManager? = null
        private var isForceStopped = false

        // Public methods
        fun isServiceRunning(): Boolean = isRunning

        fun stopBackgroundService(context: Context) {
            isForceStopped = true
            val intent = Intent(context, BackgroundServiceManager::class.java).apply {
                action = "FORCE_STOP"
            }
            context.startService(intent)
        }

        fun getStepCount(startDate: Long? = null, endDate: Long? = null): Int {
            return serviceInstance?.let { service ->
                if (service::stepCountManager.isInitialized) {
                    service.stepCountManager.getStepCount(startDate, endDate)
                } else {
                    0
                }
            } ?: 0
        }
    }

    private lateinit var sensorManager: SensorManager
    private var stepCounterSensor: Sensor? = null
    private lateinit var serviceScope: CoroutineScope
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var stepCountManager: StepCountManager

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
        createNotificationChannel()
        initializeSensors()
        initializeWakeLock()
        initializeStepManager()
        serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_SERVICE" -> {
                isForceStopped = false
                startService()
            }

            "FORCE_STOP" -> {
                isForceStopped = true
                stopService()
            }

            else -> {
                // Default action - start the service
                isForceStopped = false
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

            val notificationManager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun initializeSensors() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        Log.d(TAG, "Step counter sensor available: ${stepCounterSensor != null}")
    }

    private fun initializeWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "${packageName}:StepsCounterWakeLock"
            )
            Log.d(TAG, "Wake lock initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize wake lock: ${e.message}")
        }
    }

    private fun initializeStepManager() {
        try {
            stepCountManager = StepCountManager(this)
            Log.d(TAG, "Step count manager initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize step count manager: ${e.message}")
        }
    }

    private fun startService() {
        if (isRunning) return
        isRunning = true

        // Acquire wake lock
        acquireWakeLock()

        // Register sensor
        registerSensor()

        // Start foreground service
        startForegroundService()

        // Start background work
        startBackgroundWork()

        Log.d(TAG, "Service started successfully")
    }

    private fun stopService() {
        isRunning = false
        serviceInstance = null

        // Flush pending steps before stopping
        if (::stepCountManager.isInitialized) {
            stepCountManager.flushPendingSteps()
        }

        // Release wake lock
        releaseWakeLock()

        // Unregister sensor
        unregisterSensor()

        // Cancel coroutines
        serviceScope.cancel()

        // Stop foreground service
        stopForeground(STOP_FOREGROUND_REMOVE)

        // Stop self
        stopSelf()
    }

    private fun registerSensor() {
        stepCounterSensor?.let { sensor ->
            val success = sensorManager.registerListener(
                this, sensor, SensorManager.SENSOR_DELAY_NORMAL
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

    private fun acquireWakeLock() {
        try {
            wakeLock?.let { lock ->
                if (!lock.isHeld) {
                    lock.acquire(10 * 60 * 1000L) // 10 minutes timeout
                    Log.d(
                        TAG, "Wake lock acquired"
                    )
                } else {
                    Log.d(
                        TAG, "Wake lock already held"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(
                TAG, "Failed to acquire wake lock: ${e.message}"
            )
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                    Log.d(
                        TAG, "Wake lock released"
                    )
                } else {
                    Log.d(
                        TAG, "Wake lock not held"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(
                TAG, "Failed to release wake lock: ${e.message}"
            )
        }
    }

    private fun startForegroundService() {
        val notification = createNotification()
        startForeground(
            NOTIFICATION_ID, notification
        )
        Log.d(
            TAG, "Foreground service started"
        )
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, BackgroundServiceManager::class.java)
        val pendingIntent = PendingIntent.getService(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Get current step count for notification
        val currentSteps = if (::stepCountManager.isInitialized) {
            stepCountManager.getCurrentSessionSteps()
        } else {
            0
        }

        return NotificationCompat.Builder(
            this, CHANNEL_ID
        ).setContentTitle("Steps Counter").setContentText("Session steps: $currentSteps")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(pendingIntent)
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).build()
    }

    private fun startBackgroundWork() {
        serviceScope.launch {
            var wakeLockRenewCounter = 0

            while (isRunning) {
                try {
                    // Update notification
                    updateNotification()

                    // Renew wake lock every 5 minutes (60 iterations * 5 seconds)
                    wakeLockRenewCounter++
                    if (wakeLockRenewCounter >= 60) {
                        renewWakeLock()
                        wakeLockRenewCounter = 0
                    }

                    // Sleep for 5 seconds
                    delay(5000)
                } catch (e: Exception) {
                    Log.e(
                        TAG, "Error in background work: ${e.message}"
                    )
                }
            }
        }
    }

    private fun renewWakeLock() {
        try {
            wakeLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                    lock.acquire(10 * 60 * 1000L) // Renew for another 10 minutes
                    Log.d(
                        TAG, "Wake lock renewed"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(
                TAG, "Failed to renew wake lock: ${e.message}"
            )
        }
    }

    private fun updateNotification() {
        val notification = createNotification()
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(
            NOTIFICATION_ID, notification
        )
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let { sensorEvent ->
            if (sensorEvent.sensor.type == Sensor.TYPE_STEP_COUNTER && ::stepCountManager.isInitialized) {
                val sensorValue = sensorEvent.values[0]
                stepCountManager.onSensorChanged(sensorValue)
                Log.d(TAG, "Step sensor value: $sensorValue")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Handle accuracy changes if needed
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(
            TAG, "Task removed, scheduling restart"
        )

        // Schedule service restart
        scheduleServiceRestart()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        isRunning = false
        serviceInstance = null

        // Cleanup step manager
        if (::stepCountManager.isInitialized) {
            stepCountManager.cleanup()
        }

        // Release wake lock
        releaseWakeLock()

        // Unregister sensor
        unregisterSensor()

        // Cancel coroutines
        serviceScope.cancel()

        // Stop foreground service
        stopForeground(STOP_FOREGROUND_REMOVE)

        // Schedule restart if not force stopped
        if (!isForceStopped) {
            Log.d(
                TAG, "Service destroyed unexpectedly, scheduling restart..."
            )
            scheduleServiceRestart()
        } else {
            Log.d(
                TAG, "Service was force stopped, not restarting"
            )
        }
    }

    private fun scheduleServiceRestart() {
        try {
            val restartIntent =
                Intent(applicationContext, BackgroundServiceManager::class.java).apply {
                    action = "START_SERVICE"
                }

            val pendingIntent = PendingIntent.getService(
                applicationContext,
                1,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )

            // Simple 3-second delay for restart
            val delayMs = 3000L

            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager

            // Check if we can use exact alarms (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    // Use exact alarm if permission is available
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + delayMs,
                        pendingIntent
                    )
                    Log.d(TAG, "Service restart scheduled with exact alarm in ${delayMs}ms")
                } else {
                    // Fallback to inexact alarm
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + delayMs,
                        pendingIntent
                    )
                    Log.w(TAG, "Exact alarm permission not available, using inexact alarm")
                }
            } else {
                // For older Android versions, use exact alarm directly
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delayMs,
                    pendingIntent
                )
                Log.d(TAG, "Service restart scheduled with exact alarm in ${delayMs}ms")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule service restart: ${e.message}")

            // Try fallback with inexact alarm
            try {
                val restartIntent =
                    Intent(applicationContext, BackgroundServiceManager::class.java).apply {
                        action = "START_SERVICE"
                    }
                val pendingIntent = PendingIntent.getService(
                    applicationContext,
                    1,
                    restartIntent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
                val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 3000L,
                    pendingIntent
                )
                Log.d(TAG, "Fallback inexact alarm scheduled successfully")
            } catch (fallbackException: Exception) {
                Log.e(TAG, "Failed to schedule fallback alarm: ${fallbackException.message}")
            }
        }
    }
}