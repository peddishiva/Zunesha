package com.example.appblock.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Controller for optional Android Device Administrator protection.
 *
 * Used exclusively for consumer-facing anti-uninstall protection during focus sessions.
 * Does NOT require or configure Device Owner or Profile Owner.
 */
class DevicePolicyController(
    private val context: Context,
    private val devicePolicyManager: DevicePolicyManager? =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
) {

    companion object {
        private const val TAG = "AppBlock"
    }

    val adminComponent: ComponentName by lazy {
        AppDeviceAdminReceiver.getComponentName(context)
    }

    /**
     * Checks if App Block is registered as an active Device Administrator.
     */
    fun isAdminActive(): Boolean {
        return try {
            val active = devicePolicyManager?.isAdminActive(adminComponent) ?: false
            Log.d(TAG, "isAdminActive check: $active")
            active
        } catch (e: Exception) {
            Log.e(TAG, "Error checking isAdminActive", e)
            false
        }
    }

    /**
     * Creates an Intent to prompt the user to enable Device Administrator protection.
     * Uses standard Android system confirmation dialog.
     */
    fun createAddAdminIntent(): Intent {
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Enabling Device Administrator prevents accidental uninstallation of App Block during active focus sessions."
            )
        }
    }

    /**
     * Removes active device admin protection if user requests it.
     */
    fun removeAdmin(): Boolean {
        return try {
            if (devicePolicyManager != null && isAdminActive()) {
                devicePolicyManager.removeActiveAdmin(adminComponent)
                Log.i(TAG, "Device Admin removed successfully.")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove Device Admin", e)
            false
        }
    }
}
