package com.example.appblock.domain

import android.content.Context
import android.util.Log
import com.example.appblock.data.BlockPreferences
import com.example.appblock.model.FocusSession
import com.example.appblock.model.SessionStatus
import com.example.appblock.timer.TimerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * FocusSessionManager is the single domain source of truth for focus session lifecycle.
 * Manages start, cancellation, countdown calculations, persistence, and expiration.
 *
 * Source of truth for timing is [FocusSession.endTimeMillis].
 */
class FocusSessionManager(
    private val context: Context,
    private val preferences: BlockPreferences = BlockPreferences(context),
    private val timerManager: TimerManager = TimerManager(context),
    private val strictModeController: StrictModeController = StrictModeController()
) {

    companion object {
        private const val TAG = "AppBlock"
    }

    private val _sessionState = MutableStateFlow(preferences.loadSession())
    val sessionState: StateFlow<FocusSession> = _sessionState.asStateFlow()

    init {
        reconcileSessionState()
    }

    /**
     * Returns the current session state, reconciling expired sessions if time has passed.
     */
    fun getCurrentSession(): FocusSession {
        return reconcileSessionState()
    }

    /**
     * Checks if a given package is currently blocked.
     * Consults persisted state and checks whether the session is active and not expired.
     */
    fun isPackageBlocked(packageName: String): Boolean {
        val current = reconcileSessionState()
        return current.isPackageBlocked(packageName)
    }

    /**
     * Starts a new focus session for the specified packages.
     */
    fun startSession(
        packages: Set<String>,
        durationSeconds: Long,
        strictMode: Boolean,
        primaryAppName: String? = null
    ): SessionActionResult {
        val current = reconcileSessionState()

        val canStart = strictModeController.canStartSession(current)
        if (canStart is SessionActionResult.Rejected) {
            Log.w(TAG, "Cannot start session: ${canStart.reason}")
            return canStart
        }

        if (packages.isEmpty()) {
            return SessionActionResult.Rejected("Please select at least one application to block.")
        }

        if (durationSeconds <= 0L) {
            return SessionActionResult.Rejected("Duration must be greater than zero.")
        }

        val now = System.currentTimeMillis()
        val endTime = now + (durationSeconds * 1000L)

        val newSession = FocusSession(
            status = SessionStatus.ACTIVE,
            blockedPackages = packages,
            primaryAppLabel = primaryAppName ?: packages.firstOrNull(),
            startTimeMillis = now,
            endTimeMillis = endTime,
            totalDurationSeconds = durationSeconds,
            strictMode = strictMode
        )

        // Persist session synchronously
        preferences.saveSession(newSession)
        _sessionState.value = newSession

        // Schedule OS alarm to wake device and mark completion
        val primaryPackage = packages.firstOrNull() ?: ""
        timerManager.scheduleExpiration(
            triggerAtMillis = endTime,
            targetPackage = primaryPackage,
            appName = primaryAppName ?: primaryPackage
        )

        Log.i(TAG, "Focus session started: ${packages.size} packages blocked for ${durationSeconds}s. StrictMode=$strictMode")
        return SessionActionResult.Success
    }

    /**
     * Attempts to cancel/end the active focus session.
     * Strictly rejected if Strict Mode is active and the session has not expired.
     */
    fun cancelSession(): SessionActionResult {
        val current = reconcileSessionState()

        val validation = strictModeController.canCancelSession(current)
        if (validation is SessionActionResult.Rejected) {
            Log.w(TAG, "Cannot cancel session: ${validation.reason}")
            return validation
        }

        // Cancel scheduled alarm
        timerManager.cancelExpiration()

        // Clear or mark idle in preferences
        preferences.clearLock()
        val idleSession = FocusSession(status = SessionStatus.IDLE)
        _sessionState.value = idleSession

        Log.i(TAG, "Focus session cancelled successfully.")
        return SessionActionResult.Success
    }

    /**
     * Validates and reconciles the current session state against the clock.
     * If the session reached its endTimeMillis, marks it COMPLETED.
     */
    fun reconcileSessionState(): FocusSession {
        val stored = preferences.loadSession()
        val now = System.currentTimeMillis()

        if (stored.status == SessionStatus.ACTIVE && now >= stored.endTimeMillis) {
            Log.i(TAG, "Session expired (now=$now >= end=${stored.endTimeMillis}). Reconciling to COMPLETED.")
            preferences.markCompleted()
            val completed = stored.copy(status = SessionStatus.COMPLETED)
            _sessionState.value = completed
            return completed
        }

        _sessionState.value = stored
        return stored
    }

    /**
     * Reset completed session back to IDLE so the user can configure a new session.
     */
    fun acknowledgeCompletion() {
        preferences.clearLock()
        _sessionState.value = FocusSession(status = SessionStatus.IDLE)
    }

    /**
     * Saves user's draft package selection.
     */
    fun saveDraftSelectedPackages(packages: Set<String>): SessionActionResult {
        val current = reconcileSessionState()
        val validation = strictModeController.canModifyBlockedApps(current)
        if (validation is SessionActionResult.Rejected) {
            return validation
        }
        preferences.saveSelectedPackages(packages)
        return SessionActionResult.Success
    }

    /**
     * Gets user's draft package selection.
     */
    fun getDraftSelectedPackages(): Set<String> {
        return preferences.getSelectedPackages()
    }
}
