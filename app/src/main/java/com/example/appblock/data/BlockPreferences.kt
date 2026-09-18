package com.example.appblock.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.appblock.model.FocusSession
import com.example.appblock.model.LockState
import com.example.appblock.model.LockStatus
import com.example.appblock.model.SessionStatus

/**
 * Local persistent store for App Block timer and focus session state using SharedPreferences.
 * Synchronous and accessible from UI, AccessibilityService, and background BroadcastReceivers.
 */
class BlockPreferences(context: Context) {

    companion object {
        private const val TAG = "AppBlock"
        private const val PREFS_NAME = "app_block_prefs"

        private const val KEY_STATUS = "lock_status"
        private const val KEY_TARGET_PACKAGE = "target_package"
        private const val KEY_TARGET_APP_NAME = "target_app_name"
        private const val KEY_START_TIME_MS = "start_time_ms"
        private const val KEY_EXPIRATION_TIME_MS = "expiration_time_ms"
        private const val KEY_DURATION_SECONDS = "duration_seconds"
        private const val KEY_BLOCKED_PACKAGES = "blocked_packages_set"
        private const val KEY_SELECTED_PACKAGES = "selected_packages_draft"
        private const val KEY_STRICT_MODE = "strict_mode"
        private const val KEY_ADMIN_ENABLED = "admin_protection_enabled"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Saves a newly activated focus session.
     */
    fun saveSession(session: FocusSession) {
        Log.i(TAG, "Persisting FocusSession: packages=${session.blockedPackages.size}, strict=${session.strictMode}, expiresAt=${session.endTimeMillis}")
        val primaryPackage = session.blockedPackages.firstOrNull() ?: ""
        prefs.edit()
            .putString(KEY_STATUS, session.status.name)
            .putStringSet(KEY_BLOCKED_PACKAGES, session.blockedPackages)
            .putString(KEY_TARGET_PACKAGE, primaryPackage)
            .putString(KEY_TARGET_APP_NAME, session.primaryAppLabel ?: primaryPackage)
            .putLong(KEY_START_TIME_MS, session.startTimeMillis)
            .putLong(KEY_EXPIRATION_TIME_MS, session.endTimeMillis)
            .putLong(KEY_DURATION_SECONDS, session.totalDurationSeconds)
            .putBoolean(KEY_STRICT_MODE, session.strictMode)
            .commit()
    }

    /**
     * Retrieves the current persisted FocusSession.
     * Computes active/expired dynamically based on System.currentTimeMillis() and endTimeMillis.
     */
    fun loadSession(): FocusSession {
        val statusString = prefs.getString(KEY_STATUS, SessionStatus.IDLE.name)
        val status = try {
            SessionStatus.valueOf(statusString ?: SessionStatus.IDLE.name)
        } catch (e: Exception) {
            SessionStatus.IDLE
        }

        val blockedPackages = prefs.getStringSet(KEY_BLOCKED_PACKAGES, emptySet()) ?: emptySet()
        val primaryPackage = prefs.getString(KEY_TARGET_PACKAGE, "") ?: ""
        val appLabel = prefs.getString(KEY_TARGET_APP_NAME, "") ?: ""
        val startTimeMs = prefs.getLong(KEY_START_TIME_MS, 0L)
        val endTimeMs = prefs.getLong(KEY_EXPIRATION_TIME_MS, 0L)
        val durationSeconds = prefs.getLong(KEY_DURATION_SECONDS, 0L)
        val strictMode = prefs.getBoolean(KEY_STRICT_MODE, false)

        val packages = if (blockedPackages.isNotEmpty()) {
            blockedPackages
        } else if (primaryPackage.isNotEmpty()) {
            setOf(primaryPackage)
        } else {
            emptySet()
        }

        return FocusSession(
            status = status,
            blockedPackages = packages,
            primaryAppLabel = if (appLabel.isNotEmpty()) appLabel else packages.firstOrNull(),
            startTimeMillis = startTimeMs,
            endTimeMillis = endTimeMs,
            totalDurationSeconds = durationSeconds,
            strictMode = strictMode
        )
    }

    /**
     * Saves user's currently selected packages draft (for app selector checklist).
     */
    fun saveSelectedPackages(packages: Set<String>) {
        prefs.edit()
            .putStringSet(KEY_SELECTED_PACKAGES, packages)
            .apply()
    }

    /**
     * Retrieves user's saved draft selection of packages.
     */
    fun getSelectedPackages(): Set<String> {
        return prefs.getStringSet(KEY_SELECTED_PACKAGES, emptySet()) ?: emptySet()
    }

    /**
     * Fast check if a package is currently blocked according to the persisted active session.
     */
    fun isPackageBlocked(packageName: String): Boolean {
        val session = loadSession()
        return session.isPackageBlocked(packageName)
    }

    /**
     * Saves a newly activated block session (legacy compatibility).
     */
    fun saveActiveLock(
        packageName: String,
        appName: String,
        durationSeconds: Long,
        startTimeMs: Long = System.currentTimeMillis(),
        expirationTimeMs: Long = startTimeMs + (durationSeconds * 1000L),
        strictMode: Boolean = false
    ) {
        val session = FocusSession(
            status = SessionStatus.ACTIVE,
            blockedPackages = setOf(packageName),
            primaryAppLabel = appName,
            startTimeMillis = startTimeMs,
            endTimeMillis = expirationTimeMs,
            totalDurationSeconds = durationSeconds,
            strictMode = strictMode
        )
        saveSession(session)
    }

    /**
     * Marks the active session as completed.
     */
    fun markCompleted() {
        Log.i(TAG, "Marking session state as COMPLETED in preferences")
        prefs.edit()
            .putString(KEY_STATUS, SessionStatus.COMPLETED.name)
            .commit()
    }

    /**
     * Clears persistent session state back to IDLE.
     */
    fun clearLock() {
        Log.i(TAG, "Clearing session preferences to IDLE")
        prefs.edit()
            .putString(KEY_STATUS, SessionStatus.IDLE.name)
            .remove(KEY_TARGET_PACKAGE)
            .remove(KEY_TARGET_APP_NAME)
            .remove(KEY_START_TIME_MS)
            .remove(KEY_EXPIRATION_TIME_MS)
            .remove(KEY_DURATION_SECONDS)
            .remove(KEY_BLOCKED_PACKAGES)
            .remove(KEY_STRICT_MODE)
            .commit()
    }

    /**
     * Retrieves the current persisted LockState (for backward compatibility).
     */
    fun getLockState(): LockState {
        val session = loadSession()
        val lockStatus = when (session.status) {
            SessionStatus.ACTIVE -> if (session.isExpired) LockStatus.COMPLETED else LockStatus.ACTIVE
            SessionStatus.COMPLETED -> LockStatus.COMPLETED
            SessionStatus.IDLE -> LockStatus.IDLE
        }
        return LockState(
            status = lockStatus,
            targetPackage = session.blockedPackages.firstOrNull() ?: "",
            targetAppName = session.primaryAppLabel ?: "",
            startTimeMs = session.startTimeMillis,
            expirationTimeMs = session.endTimeMillis,
            durationSeconds = session.totalDurationSeconds
        )
    }
}

