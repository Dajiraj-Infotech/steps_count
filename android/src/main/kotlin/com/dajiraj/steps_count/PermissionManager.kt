package com.dajiraj.steps_count

import android.Manifest
import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class PermissionManager {

    companion object {
        const val PERMISSION_REQUEST_CODE = 1001

        /**
         * Check if ACTIVITY_RECOGNITION permission is granted
         */
        fun checkActivityRecognitionPermission(context: Context): Boolean {
            return when {
                SDK_INT >= Build.VERSION_CODES.Q -> {
                    // Android 10+ (API 29+): ACTIVITY_RECOGNITION permission is required
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACTIVITY_RECOGNITION
                    ) == PackageManager.PERMISSION_GRANTED
                }

                else -> {
                    // No special permission required
                    true
                }
            }
        }

        /**
         * Check if POST_NOTIFICATIONS permission is granted
         */
        fun checkPostNotificationsPermission(context: Context): Boolean {
            return when {
                SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                    // Android 13+ (API 33+): POST_NOTIFICATIONS permission is required
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                }

                else -> {
                    // Notifications are allowed by default
                    true
                }
            }
        }

        /**
         * Check if all required permissions are granted
         */
        fun checkRequiredPermissions(context: Context): Boolean {
            val isActivityRecognitionGranted = checkActivityRecognitionPermission(context)
            val isPostNotificationsGranted = checkPostNotificationsPermission(context)
            return isActivityRecognitionGranted && isPostNotificationsGranted
        }


        /**
         * Request ACTIVITY_RECOGNITION and POST_NOTIFICATIONS permissions from activity
         */
        fun requestPermissions(activity: Activity) {
            ActivityCompat.requestPermissions(
                activity, arrayOf(
                    Manifest.permission.ACTIVITY_RECOGNITION,
                    Manifest.permission.POST_NOTIFICATIONS
                ), PERMISSION_REQUEST_CODE
            )
        }
    }
}
