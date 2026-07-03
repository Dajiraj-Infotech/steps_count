package com.dajiraj.steps_count

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
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

        /** Hardware FIFO batching window (5 min) so the SoC is not woken for every step (EC-8). */
        private const val MAX_REPORT_LATENCY_US = 5 * 60 * 1_000_000

        // Device-protected-storage flags so the boot receiver can read them before first unlock.
        private const val FLAGS_PREFS = "steps_count_flags"
        private const val KEY_TRACKING_ENABLED = "tracking_enabled"
        private const val RESTART_ALARM_ID = 42

        // Service state
        private var isRunning = false
        private var serviceInstance: BackgroundServiceManager? = null

        // Single shared instance; null when the service is not running.
        @Volatile
        var stepCountManager: StepCountManager? = null
            private set

        fun isServiceRunning(): Boolean = isRunning

        private fun flagsPrefs(context: Context) =
            context.applicationContext.createDeviceProtectedStorageContext()
                .getSharedPreferences(FLAGS_PREFS, Context.MODE_PRIVATE)

        /**
         * Whether the user wants tracking on. Defaults to false so the boot receiver does not resurrect
         * tracking the user never enabled; the first startBackgroundService() sets it true (EC-42).
         */
        fun isTrackingEnabled(context: Context): Boolean =
            flagsPrefs(context).getBoolean(KEY_TRACKING_ENABLED, false)

        fun setTrackingEnabled(context: Context, enabled: Boolean) {
            flagsPrefs(context).edit().putBoolean(KEY_TRACKING_ENABLED, enabled).apply()
        }

        /** ACTIVITY_RECOGNITION is a runtime permission on API 29+; without it the sensor is silent. */
        fun hasActivityRecognitionPermission(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

        fun stopBackgroundService(context: Context) {
            setTrackingEnabled(context, false)
            val intent = Intent(context, BackgroundServiceManager::class.java).apply {
                action = "FORCE_STOP"
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // Service already dead / cannot start from background: the flag alone stops any restart.
                Log.w(TAG, "stopBackgroundService: ${e.message}")
            }
        }

        /** Best-effort restart via AlarmManager (survives process death). Honors Android 12+ limits. */
        fun scheduleRestart(context: Context, delayMs: Long) {
            try {
                val intent = Intent(context, BackgroundServiceManager::class.java).apply {
                    action = "START_SERVICE"
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    PendingIntent.getForegroundService(context, RESTART_ALARM_ID, intent, flags)
                } else {
                    PendingIntent.getService(context, RESTART_ALARM_ID, intent, flags)
                }
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + delayMs, pi)
                Log.d(TAG, "Restart alarm scheduled in ${delayMs}ms")
            } catch (e: Exception) {
                Log.e(TAG, "scheduleRestart failed: ${e.message}")
            }
        }
    }

    private lateinit var sensorManager: SensorManager
    private var stepCounterSensor: Sensor? = null
    private lateinit var serviceScope: CoroutineScope
    private var isSensorRegistered = false

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
        createNotificationChannel()
        initializeSensors()
        serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Explicit stop: never enters the foreground, cannot fabricate a start (EC-58).
        if (intent?.action == "FORCE_STOP") {
            stopTracking()
            return START_NOT_STICKY
        }

        if (intent?.action == "START_SERVICE") {
            setTrackingEnabled(applicationContext, true)
        } else if (!isTrackingEnabled(applicationContext)) {
            // Redelivered/implicit restart while the user has tracking off: do not resurrect (EC-42).
            stopSelf()
            return START_NOT_STICKY
        }

        // Prepare the manager first so the notification shows a real count, then honor the
        // startForegroundService contract on EVERY start command before any early return (EC-20).
        ensureManager()
        if (!startForegroundSafely()) {
            // API 34+ health FGS without ACTIVITY_RECOGNITION throws SecurityException; other
            // background-start limits throw too. Stop cleanly instead of crash-looping (EC-18).
            Log.e(TAG, "Could not enter foreground; stopping without restart")
            stopSelf()
            return START_NOT_STICKY
        }

        startTracking()
        return START_STICKY
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

    /** (Re)create the shared manager if absent. Idempotent; retried on every start command (EC-15/16). */
    private fun ensureManager() {
        if (Companion.stepCountManager == null) {
            try {
                Companion.stepCountManager = StepCountManager(
                    context = applicationContext,
                    onFlushSuccess = { updateNotification() },     // M1: notification on each flush
                    onSensorStalled = { reregisterSensor() }       // EC-38: re-attach a torn/frozen sensor
                )
                Log.d(TAG, "Step count manager initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize step count manager: ${e.message}")
            }
        }
    }

    /** Re-attach the sensor listener (EC-19/EC-38). Safe from any thread; registerListener is thread-safe. */
    private fun reregisterSensor() {
        if (isSensorRegistered) unregisterSensor()
        registerSensor()
    }

    private fun startTracking() {
        if (!isSensorRegistered) registerSensor()
        isRunning = true
        if (!hasActivityRecognitionPermission(applicationContext)) {
            Log.w(TAG, "ACTIVITY_RECOGNITION not granted; the sensor will deliver no events (EC-17)")
        }
        updateNotification()
        Log.d(TAG, "Tracking started (sensorRegistered=$isSensorRegistered)")
    }

    private fun stopTracking() {
        doCleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Enter the foreground, returning false (instead of crashing) if the platform refuses (EC-18). */
    private fun startForegroundSafely(): Boolean {
        return try {
            val notification = createInitialNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}")
            false
        }
    }

    private fun registerSensor() {
        stepCounterSensor?.let { sensor ->
            // maxReportLatencyUs enables hardware FIFO batching: while the SoC sleeps, the sensor hub
            // buffers events (each keeping its true timestamp) and delivers them on wake, which is both
            // battery-friendly and lets the manager attribute steps to when they happened (EC-8).
            val success = sensorManager.registerListener(
                this, sensor, SensorManager.SENSOR_DELAY_NORMAL, MAX_REPORT_LATENCY_US
            )
            isSensorRegistered = success
            if (success) Log.d(TAG, "Step counter sensor registered")
            else Log.w(TAG, "Failed to register step counter sensor")
        }
    }

    private fun unregisterSensor() {
        sensorManager.unregisterListener(this)
        isSensorRegistered = false
        Log.d(TAG, "Sensor unregistered")
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


    /**
     * Centralised cleanup, safe to call from both stopTracking() and onDestroy(). Idempotent without a
     * sticky guard (cleanup() on a null manager is a no-op), so a stop/start race cannot wedge isRunning
     * in the "true but not really running" state the old guard could leave behind (EC-16).
     */
    private fun doCleanup() {
        isRunning = false
        if (isSensorRegistered) unregisterSensor()
        stepCountManager?.cleanup()
        Companion.stepCountManager = null
        if (serviceInstance === this) serviceInstance = null
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
        // Accuracy transitions are logged by the manager via the event stream; nothing to do here.
    }

    /**
     * The user swiped the app off the recents list, which kills the process on many OEMs. If tracking
     * is still enabled, arm an alarm to bring the service back (best-effort; Android 12+ background
     * foreground-service-start limits may still block it, EC-23/EC-41).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (isTrackingEnabled(applicationContext)) {
            scheduleRestart(applicationContext, 2_000L)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        doCleanup()
        if (::serviceScope.isInitialized) serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}