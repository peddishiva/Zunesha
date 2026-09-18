package com.example.appblock.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Standard Android DeviceAdminReceiver for App Block.
 * Used exclusively for optional anti-uninstall protection during focus sessions.
 * Does NOT require or use Device Owner or ADB provisioning.
 */
class AppDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "AppBlock"

        /**
         * Returns the ComponentName identifying this DeviceAdminReceiver.
         */
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, AppDeviceAdminReceiver::class.java)
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Optional Device Admin protection enabled for App Block.")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "Optional Device Admin protection disabled for App Block.")
    }
}

