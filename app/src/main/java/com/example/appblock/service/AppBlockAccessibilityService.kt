package com.example.appblock.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.appblock.domain.FocusSessionManager
import com.example.appblock.ui.BlockScreenActivity

/**
 * Android AccessibilityService responsible for foreground application detection.
 *
 * Privacy Guarantees:
 * - Does NOT read screen text, messages, passwords, keystrokes, or notifications.
 * - Does NOT inspect accessibility node trees (canRetrieveWindowContent is false).
 * - All processing is strictly local to the device.
 * - Only inspects event.packageName to identify foreground window changes.
 */
class AppBlockAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AppBlockAccessService"
        private const val EVENT_DEBOUNCE_MS = 600L
    }

    private lateinit var sessionManager: FocusSessionManager
    private var lastBlockedPackage: String? = null
    private var lastBlockTriggerTime: Long = 0L

    override fun onCreate() {
        super.onCreate()
        sessionManager = FocusSessionManager(applicationContext)
        Log.i(TAG, "AppBlockAccessibilityService created and initialized.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return

        // Never block App Block itself
        if (packageName == applicationContext.packageName) return

        // Consult persisted active focus session
        if (sessionManager.isPackageBlocked(packageName)) {
            val now = System.currentTimeMillis()

            // Debounce rapid continuous events for the same package
            if (packageName == lastBlockedPackage && (now - lastBlockTriggerTime) < EVENT_DEBOUNCE_MS) {
                return
            }

            lastBlockedPackage = packageName
            lastBlockTriggerTime = now

            Log.i(TAG, "Blocked package detected in foreground: $packageName. Showing BlockScreenActivity.")

            try {
                val blockIntent = Intent(this, BlockScreenActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(BlockScreenActivity.EXTRA_BLOCKED_PACKAGE, packageName)
                }
                startActivity(blockIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch BlockScreenActivity for package $packageName", e)
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AppBlockAccessibilityService interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "AppBlockAccessibilityService destroyed.")
    }
}
