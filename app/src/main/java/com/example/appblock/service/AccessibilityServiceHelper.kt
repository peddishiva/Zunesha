package com.example.appblock.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager

/**
 * Utility to verify whether AppBlockAccessibilityService is currently enabled by the user in Android Settings.
 */
object AccessibilityServiceHelper {

    private const val TAG = "AppBlock"

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val expectedClassName = AppBlockAccessibilityService::class.java.name

        // Check active running accessibility services
        if (am != null) {
            try {
                val runningServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                for (service in runningServices) {
                    val serviceInfo = service.resolveInfo?.serviceInfo
                    if (serviceInfo != null &&
                        serviceInfo.packageName == context.packageName &&
                        serviceInfo.name == expectedClassName
                    ) {
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking enabled accessibility service list", e)
            }
        }

        // Secondary fallback check in Settings.Secure
        try {
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val expectedShort = "${context.packageName}/.service.AppBlockAccessibilityService"
            val expectedLong = "${context.packageName}/$expectedClassName"

            return enabledServicesSetting.contains(expectedShort) ||
                    enabledServicesSetting.contains(expectedLong) ||
                    enabledServicesSetting.contains("AppBlockAccessibilityService")
        } catch (e: Exception) {
            Log.w(TAG, "Error checking Settings.Secure for accessibility service", e)
            return false
        }
    }

    /**
     * Intent to open the Android Accessibility Settings screen.
     */
    fun getAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
