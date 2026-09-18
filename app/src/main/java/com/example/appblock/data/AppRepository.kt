package com.example.appblock.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import com.example.appblock.model.InstalledApp

/**
 * Repository to query and filter installed user applications for focus blocking.
 */
class AppRepository(
    private val context: Context,
    private val blockPreferences: BlockPreferences = BlockPreferences(context)
) {

    companion object {
        private const val TAG = "AppBlock"

        // Packages that should never be blocked for phone usability & system stability
        val CRITICAL_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.providers.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.deskclock",
            "com.android.deskclock",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.emergency"
        )
    }

    /**
     * Queries launchable applications on the device, excluding App Block, launchers, and critical system packages.
     */
    fun getLaunchableApps(): List<InstalledApp> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfos = try {
            pm.queryIntentActivities(mainIntent, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query launchable activities", e)
            emptyList()
        }

        val currentPackage = context.packageName
        val defaultLauncherPackage = getDefaultLauncherPackage(pm)
        val selectedPackages = blockPreferences.getSelectedPackages()

        val appList = mutableListOf<InstalledApp>()
        val seenPackages = mutableSetOf<String>()

        for (resolveInfo in resolveInfos) {
            val pkgName = resolveInfo.activityInfo.packageName

            // Avoid duplicates across multiple launcher activities in the same package
            if (seenPackages.contains(pkgName)) continue
            seenPackages.add(pkgName)

            // Exclude self
            if (pkgName == currentPackage) continue

            // Exclude current default launcher to prevent soft-bricking user navigation
            if (pkgName == defaultLauncherPackage) continue

            // Exclude obvious critical system packages
            if (CRITICAL_PACKAGES.contains(pkgName)) continue

            try {
                val appInfo = pm.getApplicationInfo(pkgName, 0)

                // Check if package is critical system app without user updates
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

                // Allow popular user apps and updated system apps (e.g. Chrome, YouTube)
                val isEligible = !isSystem || isUpdatedSystem || isCommonUserApp(pkgName)

                val label = resolveInfo.loadLabel(pm).toString()
                val icon = try {
                    resolveInfo.loadIcon(pm)
                } catch (e: Exception) {
                    null
                }

                appList.add(
                    InstalledApp(
                        packageName = pkgName,
                        appName = label,
                        icon = icon,
                        isSelected = selectedPackages.contains(pkgName),
                        isSuspended = false,
                        isEligible = isEligible
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not retrieve info for package: $pkgName", e)
            }
        }

        // Sort alphabetically by app name
        return appList.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.appName })
    }

    /**
     * Resolves the current default home/launcher application package.
     */
    fun getDefaultLauncherPackage(pm: PackageManager = context.packageManager): String? {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfo = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName
    }

    /**
     * Verifies if a package is safe to block (never self, launcher, or critical system package).
     */
    fun isSafeToBlock(packageName: String): Boolean {
        if (packageName == context.packageName) return false
        if (CRITICAL_PACKAGES.contains(packageName)) return false
        val defaultLauncher = getDefaultLauncherPackage()
        if (packageName == defaultLauncher) return false
        return true
    }

    /**
     * Returns true if the package is a known user-facing app that might be installed as a system app.
     */
    private fun isCommonUserApp(packageName: String): Boolean {
        return packageName.startsWith("com.google.android.youtube") ||
                packageName.startsWith("com.android.chrome") ||
                packageName.startsWith("com.google.android.apps.maps") ||
                packageName.startsWith("com.google.android.apps.photos") ||
                packageName.startsWith("com.instagram") ||
                packageName.startsWith("com.facebook") ||
                packageName.startsWith("com.snapchat") ||
                packageName.startsWith("com.twitter") ||
                packageName.startsWith("com.zhiliaoapp.musically")
    }
}

