package com.example.appblock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.appblock.data.BlockPreferences
import com.example.appblock.timer.TimerManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * BroadcastReceiver invoked by AlarmManager when a focus timer expires.
 * Marks the session as completed, allowing normal application access.
 */
class BlockExpirationReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AppBlock"

        // In-memory event stream to notify active ViewModel / UI when timer completes
        private val _expirationEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
        val expirationEvents: SharedFlow<String> = _expirationEvents.asSharedFlow()
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.i(TAG, "BlockExpirationReceiver triggered with action: ${intent?.action}")

        val prefs = BlockPreferences(context)
        val session = prefs.loadSession()

        val targetPackage = intent?.getStringExtra(TimerManager.EXTRA_PACKAGE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: session.blockedPackages.firstOrNull() ?: ""

        val targetAppName = intent?.getStringExtra(TimerManager.EXTRA_APP_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: session.primaryAppLabel ?: targetPackage

        Log.i(TAG, "Focus timer reached zero for package '$targetPackage' ($targetAppName). Marking session completed.")
        prefs.markCompleted()
        _expirationEvents.tryEmit(targetPackage)
    }
}

