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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            result.success(true)
        } catch (e: Exception) {
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

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }
}
