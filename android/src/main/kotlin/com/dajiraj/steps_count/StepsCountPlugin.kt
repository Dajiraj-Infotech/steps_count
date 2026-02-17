package com.dajiraj.steps_count

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

/** StepsCountPlugin */
class StepsCountPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {

    companion object {
        private const val TAG = "StepsCountPlugin"
    }

    private lateinit var channel: MethodChannel
    private var context: Context? = null
    private var activity: android.app.Activity? = null
    private lateinit var stepCountManager: StepCountManager

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "steps_count")
        channel.setMethodCallHandler(this)
        StepCountManager.stepCountChannel = channel
        context = flutterPluginBinding.applicationContext
        initializeStepManager(flutterPluginBinding.applicationContext)
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
            "exportStepsDatabase" -> exportStepsDatabase(result)
            else -> result.notImplemented()
        }
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

    private fun initializeStepManager(context: Context) {
        try {
            stepCountManager = StepCountManager(context)
            Log.d(TAG, "Step count manager initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize step count manager: ${e.message}")
        }
    }

    private fun getTodaysCount(result: Result) {
        try {
            // Get today's step count from service
            val todaysCount = stepCountManager.getTodaysCount()
            result.success(todaysCount)
        } catch (e: Exception) {
            result.error("TODAYS_COUNT_ERROR", "Failed to get today's count: ${e.message}", null)
        }
    }

    private fun getStepCount(call: MethodCall, result: Result) {
        try {
            // Extract date parameters if provided
            val startDate = call.argument<Long>("startDate")
            val endDate = call.argument<Long>("endDate")

            // Get step count from service
            val stepCount = stepCountManager.getStepCount(startDate, endDate)
            result.success(stepCount)
        } catch (e: Exception) {
            result.error("STEP_COUNT_ERROR", "Failed to get step count: ${e.message}", null)
        }
    }

    private fun getTimeline(call: MethodCall, result: Result) {
        try {
            // Extract parameters
            val startDate = call.argument<Long>("startDate")
            val endDate = call.argument<Long>("endDate")
            val timeZone = TimeZoneType.fromString(call.argument<String>("timeZone"))

            // Get timeline data from service
            val timelineData = stepCountManager.getTimeline(startDate, endDate, timeZone)
            result.success(timelineData)
        } catch (e: Exception) {
            result.error("TIMELINE_ERROR", "Failed to get timeline data: ${e.message}", null)
        }
    }

    private fun exportStepsDatabase(result: Result) {
        try {
            val context = this.context ?: run {
                result.error("CONTEXT_ERROR", "Context not available", null)
                return
            }

            // Get the database file path
            val dbName = "step_count.db"
            val dbFile = context.getDatabasePath(dbName)

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

            // Copy file
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
    }
}
