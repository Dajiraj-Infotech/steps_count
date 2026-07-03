package com.dajiraj.steps_count

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.util.concurrent.Executors

/** StepsCountPlugin */
class StepsCountPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {

    companion object {
        private const val TAG = "StepsCountPlugin"
    }

    private lateinit var channel: MethodChannel
    private var context: Context? = null
    private var activity: android.app.Activity? = null

    // Reads run off the platform thread so a large getTimeline/getTimelineAfter cannot ANR the host
    // app (EC-45); results are posted back on the main thread as the method channel requires.
    private val ioExecutor = Executors.newSingleThreadExecutor()
    // Lazy so merely constructing the plugin (e.g. in a JVM unit test) does not touch the main Looper.
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** Coerce a Dart numeric argument to Long, tolerating Int-encoded values (EC-44). */
    private fun MethodCall.longArg(key: String): Long? = (argument(key) as? Number)?.toLong()

    /** Run [compute] on the IO executor and reply on the main thread; map exceptions to result.error. */
    private fun replyAsync(result: Result, errorCode: String, compute: () -> Any?) {
        ioExecutor.execute {
            val outcome = runCatching(compute)
            mainHandler.post {
                outcome
                    .onSuccess { result.success(it) }
                    .onFailure { result.error(errorCode, it.message, null) }
            }
        }
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "steps_count")
        channel.setMethodCallHandler(this)
        StepCountManager.stepCountChannel = channel
        context = flutterPluginBinding.applicationContext
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "startBackgroundService" -> startBackgroundService(result)
            "stopBackgroundService" -> stopBackgroundService(result)
            "isServiceRunning" -> isServiceRunning(result)
            "getTodaysCount" -> getTodaysCount(result)
            "getStepCount" -> getStepCount(call, result)
            "getTimeline" -> getTimeline(call, result)
            "getTimelineAfter" -> getTimelineAfter(call, result)
            "getStepSources" -> result.success(emptyList<Any>()) // iOS HealthKit only
            "exportStepsDatabase" -> exportStepsDatabase(result)
            "startStepObserver" -> result.success(true) // iOS-only; no-op on Android
            "getTrackingStatus" -> getTrackingStatus(result)
            else -> result.notImplemented()
        }
    }

    private fun getTrackingStatus(result: Result) {
        val ctx = context ?: run { result.error("CONTEXT_ERROR", "Context not available", null); return }
        val manager = BackgroundServiceManager.stepCountManager
        val sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensorAvailable = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
        val status = mapOf(
            "serviceRunning" to BackgroundServiceManager.isServiceRunning(),
            "trackingEnabled" to BackgroundServiceManager.isTrackingEnabled(ctx),
            "sensorAvailable" to sensorAvailable,
            "permissionGranted" to BackgroundServiceManager.hasActivityRecognitionPermission(ctx),
            "notificationsGranted" to NotificationManagerCompat.from(ctx).areNotificationsEnabled(),
            "lastEventAgeMs" to (manager?.getLastEventAgeMs() ?: -1L),
            "lastProgressAgeMs" to (manager?.getLastProgressAgeMs() ?: -1L)
        )
        result.success(status)
    }

    private fun startBackgroundService(result: Result) {
        try {
            val context = this.context ?: run {
                result.error("CONTEXT_ERROR", "Context not available", null)
                return
            }

            // Check if service is already running
            if (BackgroundServiceManager.isServiceRunning()) {
                result.success(true)
                return
            }

            // Start the service
            val serviceIntent = Intent(context, BackgroundServiceManager::class.java).apply {
                action = "START_SERVICE"
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.d(TAG, "Background service start requested")
                result.success(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground service", e)
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && 
                    e.javaClass.simpleName == "ForegroundServiceStartNotAllowedException" -> {
                        result.error(
                            "FOREGROUND_SERVICE_ERROR",
                            "Cannot start foreground service from background on Android 12+. " +
                            "Please ensure the app is in the foreground when starting the service.",
                            e.message
                        )
                    }
                    else -> {
                        result.error("SERVICE_START_ERROR", "Failed to start service: ${e.message}", null)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in startBackgroundService", e)
            result.error("SERVICE_ERROR", "Failed to start service: ${e.message}", null)
        }
    }

    private fun stopBackgroundService(result: Result) {
        try {
            val context = this.context ?: run {
                result.error("CONTEXT_ERROR", "Context not available", null)
                return
            }

            // Force stop the service
            BackgroundServiceManager.stopBackgroundService(context)
            result.success(true)
        } catch (e: Exception) {
            result.error("SERVICE_ERROR", "Failed to force stop service: ${e.message}", null)
        }
    }

    private fun isServiceRunning(result: Result) {
        try {
            val isRunning = BackgroundServiceManager.isServiceRunning()
            result.success(isRunning)
        } catch (e: Exception) {
            result.error("SERVICE_STATUS_ERROR", "Failed to get service status: ${e.message}", null)
        }
    }


    private fun getTodaysCount(result: Result) {
        val ctx = context ?: run { result.error("CONTEXT_ERROR", "Context not available", null); return }
        // Uses the running manager (includes unflushed steps) or falls back to a direct DB read so the
        // user does not see 0 after the service was killed (EC-30).
        replyAsync(result, "TODAYS_COUNT_ERROR") {
            BackgroundServiceManager.stepCountManager?.getTodaysCount()
                ?: StepCountManager.readTodaysCount(ctx)
        }
    }

    private fun getStepCount(call: MethodCall, result: Result) {
        val ctx = context ?: run { result.error("CONTEXT_ERROR", "Context not available", null); return }
        val startDate = call.longArg("startDate")
        val endDate = call.longArg("endDate")
        replyAsync(result, "STEP_COUNT_ERROR") {
            BackgroundServiceManager.stepCountManager?.getStepCount(startDate, endDate)
                ?: StepCountManager.readStepCount(ctx, startDate, endDate)
        }
    }

    private fun getTimeline(call: MethodCall, result: Result) {
        val ctx = context ?: run { result.error("CONTEXT_ERROR", "Context not available", null); return }
        val startDate = call.longArg("startDate")
        val endDate = call.longArg("endDate")
        val timeZone = TimeZoneType.fromString(call.argument<String>("timeZone"))
        replyAsync(result, "TIMELINE_ERROR") {
            BackgroundServiceManager.stepCountManager?.getTimeline(startDate, endDate, timeZone)
                ?: StepCountManager.readTimeline(ctx, startDate, endDate, timeZone)
        }
    }

    private fun getTimelineAfter(call: MethodCall, result: Result) {
        val ctx = context ?: run { result.error("CONTEXT_ERROR", "Context not available", null); return }
        val lastSyncTimestamp = call.longArg("lastSyncTimestamp") // null = return all
        replyAsync(result, "TIMELINE_AFTER_ERROR") {
            BackgroundServiceManager.stepCountManager?.getTimelineAfter(lastSyncTimestamp)
                ?: StepCountManager.readTimelineAfter(ctx, lastSyncTimestamp)
        }
    }

    private fun exportStepsDatabase(result: Result) {
        try {
            val context = this.context ?: run {
                result.error("CONTEXT_ERROR", "Context not available", null)
                return
            }

            // Get the database file path. Phase 4 moved the DB into device-protected storage, so read
            // it from there; fall back to the legacy credential-encrypted path for un-migrated installs.
            val dbName = "step_count.db"
            val dpsContext = context.createDeviceProtectedStorageContext()
            var dbFile = dpsContext.getDatabasePath(dbName)
            if (!dbFile.exists()) {
                dbFile = context.getDatabasePath(dbName)
            }

            if (!dbFile.exists()) {
                result.error("DATABASE_NOT_FOUND", "Steps database (step_count.db) not found", null)
                return
            }

            // Determine export location (using cacheDir as requested, fallback to external files dir)
            val exportDir = context.cacheDir ?: context.getExternalFilesDir(null)
            
            if (exportDir == null) {
                result.error("EXPORT_DIR_ERROR", "Cannot find suitable export directory", null)
                return
            }

            // Create destination file
            val exportFile = java.io.File(exportDir, "step_count_export.db")

            // Run full WAL checkpoint so the copy is consistent. Use same DB instance when available.
            val checkpointOk = BackgroundServiceManager.stepCountManager?.runWalCheckpointForExport()
                ?: run {
                    android.database.sqlite.SQLiteDatabase.openDatabase(
                        dbFile.absolutePath, null,
                        android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
                    ).use { db ->
                        try {
                            db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
                            Log.d(TAG, "WAL checkpoint (FULL) completed (export fallback)")
                            true
                        } catch (e: Exception) {
                            Log.e(TAG, "WAL checkpoint failed during export: ${e.message}")
                            false
                        }
                    }
                }
            if (!checkpointOk) {
                Log.w(TAG, "Export proceeding after checkpoint failure; copy may be inconsistent")
            }

            // Copy the now-consistent database file
            copyFile(dbFile, exportFile)

            Log.d(TAG, "Database exported to: ${exportFile.absolutePath}")
            result.success(exportFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export database", e)
            result.error("EXPORT_ERROR", "Failed to export database: ${e.message}", null)
        }
    }

    private fun copyFile(source: java.io.File, dest: java.io.File) {
        source.inputStream().use { input ->
            dest.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        // Clear the static channel if it still points at THIS engine's channel, so the long-running
        // service does not keep invoking a dead messenger (EC-57).
        if (StepCountManager.stepCountChannel === channel) {
            StepCountManager.stepCountChannel = null
        }
        ioExecutor.shutdown()
    }
}
