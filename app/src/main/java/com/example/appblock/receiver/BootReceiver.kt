package com.example.appblock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.appblock.data.BlockPreferences
import com.example.appblock.model.SessionStatus
import com.example.appblock.timer.TimerManager

/**
 * Handles device boot (BOOT_COMPLETED) to ensure session consistency.
 * If a session was active across a device reboot, either marks it completed if expired
 * or restores the alarm schedule if still active.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AppBlock"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(TAG, "BootReceiver received BOOT_COMPLETED. Checking persistent session state...")

        val prefs = BlockPreferences(context)
        val session = prefs.loadSession()

        if (session.status != SessionStatus.ACTIVE) {
            Log.d(TAG, "No active session on reboot.")
            return
        }

        val now = System.currentTimeMillis()

        if (now >= session.endTimeMillis) {
            Log.i(TAG, "Session expired while device was powered off. Marking completed...")
            prefs.markCompleted()
        } else {
            val remainingSeconds = (session.endTimeMillis - now) / 1000L
            Log.i(TAG, "Session is still active after reboot (expires in ${remainingSeconds}s). Rescheduling expiration alarm...")
            val primaryPackage = session.blockedPackages.firstOrNull() ?: ""
            try {
                val timerManager = TimerManager(context)
                val exactAlarmAvailable = timerManager.canScheduleExactAlarms()
                Log.i(TAG, "BootReceiver exact alarm available: $exactAlarmAvailable")
                timerManager.scheduleExpiration(
                    triggerAtMillis = session.endTimeMillis,
                    targetPackage = primaryPackage,
                    appName = session.primaryAppLabel ?: primaryPackage
                )
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "Failed to reschedule alarm on boot (non-fatal; endTimeMillis remains authoritative and will reconcile upon app/accessibility event): ${e.message}",
                    e
                )
            }
        }
    }
}

