package com.dajiraj.steps_count

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Restarts the step service after a reboot or app update, but ONLY if the user still has tracking
 * enabled (EC-42). Uses an AlarmManager retry rather than Handler.postDelayed, which does not survive
 * a receiver-only process being killed after onReceive returns (EC-28). The dead ACTION_USER_UNLOCKED
 * / ACTION_USER_PRESENT handling was removed: those are not delivered to manifest receivers on modern
 * Android (EC-26). Pre-unlock start works because the service is directBootAware and its storage is
 * device-protected (EC-29).
 */
class BootServiceManager : BroadcastReceiver() {
    companion object {
        private const val TAG = "StepsServiceReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                if (BackgroundServiceManager.isTrackingEnabled(context)) {
                    startStepsService(context)
                } else {
                    Log.d(TAG, "Tracking disabled; not starting on ${intent.action}")
                }
            }

            else -> Log.w(TAG, "Unhandled action: ${intent.action}")
        }
    }

    private fun startStepsService(context: Context) {
        try {
            Log.d(TAG, "Starting steps service on boot/update (SDK ${Build.VERSION.SDK_INT})")
            val serviceIntent = Intent(context, BackgroundServiceManager::class.java).apply {
                action = "START_SERVICE"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            // OEM background-start restriction or transient failure: retry via an alarm, which
            // survives this receiver-only process being killed immediately after onReceive returns.
            Log.e(TAG, "Boot start failed (${e.message}); scheduling alarm retry")
            BackgroundServiceManager.scheduleRestart(context, 5_000L)
        }
    }
}
