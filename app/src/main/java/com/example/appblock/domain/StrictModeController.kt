package com.example.appblock.domain

import com.example.appblock.model.FocusSession
import com.example.appblock.model.SessionStatus

sealed class SessionActionResult {
    object Success : SessionActionResult()
    data class Rejected(val reason: String) : SessionActionResult()
}

/**
 * Enforces Strict Mode at the application/domain state layer.
 * While Strict Mode is active:
 * - Reject cancelSession()
 * - Reject modifyBlockedApps()
 * - Reject changeDuration()
 * - Reject changing Strict Mode
 * - Reject starting a replacement session
 */
class StrictModeController {

    fun canCancelSession(session: FocusSession): SessionActionResult {
        if (session.status == SessionStatus.ACTIVE && session.strictMode && !session.isExpired) {
            return SessionActionResult.Rejected(
                "Strict Mode is active. Session cannot be cancelled until the timer expires."
            )
        }
        return SessionActionResult.Success
    }

    fun canModifyBlockedApps(session: FocusSession): SessionActionResult {
        if (session.status == SessionStatus.ACTIVE && session.strictMode && !session.isExpired) {
            return SessionActionResult.Rejected(
                "Strict Mode is active. Blocked apps cannot be modified during an active session."
            )
        }
        return SessionActionResult.Success
    }

    fun canChangeDuration(session: FocusSession): SessionActionResult {
        if (session.status == SessionStatus.ACTIVE && session.strictMode && !session.isExpired) {
            return SessionActionResult.Rejected(
                "Strict Mode is active. Duration cannot be changed during an active session."
            )
        }
        return SessionActionResult.Success
    }

    fun canChangeStrictMode(session: FocusSession): SessionActionResult {
        if (session.status == SessionStatus.ACTIVE && session.strictMode && !session.isExpired) {
            return SessionActionResult.Rejected(
                "Strict Mode is active and cannot be toggled off while a session is running."
            )
        }
        return SessionActionResult.Success
    }

    fun canStartSession(currentSession: FocusSession): SessionActionResult {
        if (currentSession.status == SessionStatus.ACTIVE && currentSession.strictMode && !currentSession.isExpired) {
            return SessionActionResult.Rejected(
                "A strict focus session is currently in progress."
            )
        }
        return SessionActionResult.Success
    }
}
