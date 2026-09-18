package com.example.appblock.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.appblock.receiver.BlockExpirationReceiver

/**
 * Manages OS-level alarm scheduling for timer expiration using AlarmManager.
 * Ensures unsuspension triggers even when App Block is killed or in the background.
 *
 * Implements graceful degradation for Android 12+ (API 31+) exact alarm permission:
 * - Checks canScheduleExactAlarms() before calling setExactAndAllowWhileIdle
 * - Catches SecurityException if permission is revoked or rejected by OEM
 * - Falls back to setAndAllowWhileIdle() without crashing
 * - endTimeMillis remains the authoritative clock source of truth
 */
class TimerManager(
    private val context: Context,
    private val alarmManager: AlarmManager? = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
) {

    companion object {
        private const val TAG = "AppBlock"
        const val ACTION_EXPIRE_BLOCK = "com.example.appblock.ACTION_EXPIRE_BLOCK"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_EXPIRATION_TIME_MS = "extra_expiration_time_ms"
        private const val REQUEST_CODE = 9001
    }

    /**
     * Checks if the app currently has permission to schedule exact alarms.
     * On Android 12+ (API 31+), delegates to AlarmManager.canScheduleExactAlarms().
     * On earlier Android versions, always returns true.
     */
    fun canScheduleExactAlarms(): Boolean {
        val am = alarmManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                am.canScheduleExactAlarms()
            } catch (e: Exception) {
                Log.w(TAG, "Error checking canScheduleExactAlarms: ${e.message}")
                false
            }
        } else {
            true
        }
    }

    /**
     * Creates an Intent to open the system Settings screen where the user can grant
     * "Alarms & reminders" (SCHEDULE_EXACT_ALARM) permission on Android 12+.
     * Returns null on Android 11 and lower.
     */
    fun getExactAlarmSettingsIntent(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            null
        }
    }

    /**
     * Schedules OS alarm to trigger expiration when the timer reaches zero.
     * Gracefully handles cases where exact alarm access is unavailable or denied.
     */
    fun scheduleExpiration(
        triggerAtMillis: Long,
        targetPackage: String,
        appName: String
    ) {
        if (alarmManager == null) {
            Log.e(TAG, "AlarmManager service is unavailable. Cannot schedule expiration alarm.")
            return
        }

        val now = System.currentTimeMillis()
        if (triggerAtMillis <= now) {
            Log.w(TAG, "Expiration trigger time is in the past ($triggerAtMillis <= $now). Triggering immediate expiration.")
            triggerImmediateExpiration(targetPackage, appName, triggerAtMillis)
            return
        }

        val intent = Intent(context, BlockExpirationReceiver::class.java).apply {
            action = ACTION_EXPIRE_BLOCK
            putExtra(EXTRA_PACKAGE_NAME, targetPackage)
            putExtra(EXTRA_APP_NAME, appName)
            putExtra(EXTRA_EXPIRATION_TIME_MS, triggerAtMillis)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val secondsUntilExpire = (triggerAtMillis - now) / 1000L
        Log.i(
            TAG,
            "Scheduling OS alarm for package '$targetPackage' at $triggerAtMillis (in ${secondsUntilExpire}s)"
        )

        val exactPermitted = canScheduleExactAlarms()

        try {
            if (exactPermitted) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                Log.i(TAG, "AlarmManager exact alarm scheduled successfully (setExactAndAllowWhileIdle)")
            } else {
                Log.w(TAG, "Exact alarm permission not granted. Falling back to setAndAllowWhileIdle.")
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                Log.i(TAG, "AlarmManager inexact alarm scheduled successfully (fallback)")
            }
        } catch (se: SecurityException) {
            Log.w(TAG, "SecurityException scheduling exact alarm. Falling back to setAndAllowWhileIdle.", se)
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                Log.i(TAG, "Fallback alarm scheduled successfully after SecurityException")
            } catch (fallbackEx: Exception) {
                Log.e(TAG, "Failed to schedule fallback alarm. Expiration will still reconcile via clock.", fallbackEx)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error scheduling alarm. Expiration will still reconcile via clock.", e)
        }
    }

    private fun triggerImmediateExpiration(targetPackage: String, appName: String, expirationTimeMs: Long) {
        try {
            val intent = Intent(context, BlockExpirationReceiver::class.java).apply {
                action = ACTION_EXPIRE_BLOCK
                putExtra(EXTRA_PACKAGE_NAME, targetPackage)
                putExtra(EXTRA_APP_NAME, appName)
                putExtra(EXTRA_EXPIRATION_TIME_MS, expirationTimeMs)
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send immediate expiration broadcast", e)
        }
    }

    /**
     * Cancels any pending expiration alarm.
     */
    fun cancelExpiration() {
        if (alarmManager == null) return

        val intent = Intent(context, BlockExpirationReceiver::class.java).apply {
            action = ACTION_EXPIRE_BLOCK
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

        if (pendingIntent != null) {
            Log.i(TAG, "Canceling scheduled expiration alarm")
            try {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "Error canceling alarm: ${e.message}")
            }
        }
    }
}
