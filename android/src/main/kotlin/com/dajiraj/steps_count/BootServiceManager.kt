package com.dajiraj.steps_count

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

class BootServiceManager : BroadcastReceiver() {
    companion object {
        private const val TAG = "StepsServiceReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, "android.intent.action.QUICKBOOT_POWERON" -> {
                startStepsService(context)
            }

            Intent.ACTION_USER_UNLOCKED, Intent.ACTION_USER_PRESENT -> {
                checkAndStartService(context)
            }

            else -> {
                Log.w(TAG, "❓ UNKNOWN ACTION: ${intent.action}")
            }
        }
    }

    private fun checkAndStartService(context: Context) {
        Log.d(TAG, "🔍 Checking if service is running...")

        val isRunning = BackgroundServiceManager.isServiceRunning()
        Log.d(TAG, "🔍 Service running status: $isRunning")

        if (!isRunning) {
            Log.d(TAG, "🚀 Service not running, starting immediately...")
            startStepsService(context)
        }
    }

    private fun startStepsService(context: Context) {
        try {
            Log.d(TAG, "🚀 STARTING STEPS SERVICE...")
            Log.d(TAG, "📱 Android version: ${Build.VERSION.SDK_INT}")
            Log.d(TAG, "📦 Package: ${context.packageName}")

            val serviceIntent = Intent(context, BackgroundServiceManager::class.java).apply {
                action = "START_SERVICE"
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Log.d(TAG, "📲 Starting foreground service (Android O+)...")
                val result = context.startForegroundService(serviceIntent)
                Log.d(TAG, "📲 Foreground service start result: $result")
            } else {
                Log.d(TAG, "📲 Starting regular service (Android < O)...")
                val result = context.startService(serviceIntent)
                Log.d(TAG, "📲 Regular service start result: $result")
            }

            // Verify service started
            Handler(Looper.getMainLooper()).postDelayed({
                val isRunning = BackgroundServiceManager.isServiceRunning()
                if (isRunning) {
                    Log.d(TAG, "✅ SERVICE STARTED SUCCESSFULLY!")
                } else {
                    Log.w(TAG, "⚠️ SERVICE MAY NOT HAVE STARTED, retrying...")
                    retryStartService(context)
                }
            }, 3000)

        } catch (e: Exception) {
            Log.e(TAG, "❌ FAILED TO START SERVICE: ${e.message}")
            Log.e(TAG, "❌ Exception details: ${e.javaClass.simpleName}")
            e.printStackTrace()

            // Try retry even on exception
            Handler(Looper.getMainLooper()).postDelayed({
                Log.d(TAG, "🔄 Retrying service start after exception...")
                retryStartService(context)
            }, 2000)
        }
    }

    private fun retryStartService(context: Context) {
        try {
            Log.d(TAG, "🔄 RETRY: Starting service...")

            val serviceIntent = Intent(context, BackgroundServiceManager::class.java).apply {
                action = "START_SERVICE"
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Log.d(TAG, "🔄 RETRY: Starting foreground service...")
                context.startForegroundService(serviceIntent)
            } else {
                Log.d(TAG, "🔄 RETRY: Starting regular service...")
                context.startService(serviceIntent)
            }

            Log.d(TAG, "🔄 RETRY: Service start command sent")
        } catch (e: Exception) {
            Log.e(TAG, "❌ RETRY FAILED: ${e.message}")
            e.printStackTrace()
        }
    }
}