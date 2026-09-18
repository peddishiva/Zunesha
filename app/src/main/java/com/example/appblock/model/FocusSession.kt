package com.example.appblock.model

enum class SessionStatus {
    IDLE,
    ACTIVE,
    COMPLETED
}

/**
 * Immutable domain representation of a focus session.
 * The source of truth for duration and remaining time is [endTimeMillis].
 */
data class FocusSession(
    val status: SessionStatus = SessionStatus.IDLE,
    val blockedPackages: Set<String> = emptySet(),
    val primaryAppLabel: String? = null,
    val startTimeMillis: Long = 0L,
    val endTimeMillis: Long = 0L,
    val totalDurationSeconds: Long = 0L,
    val strictMode: Boolean = false,
    val sessionName: String = "Focus Session"
) {
    val isActive: Boolean
        get() = status == SessionStatus.ACTIVE && System.currentTimeMillis() < endTimeMillis

    val isExpired: Boolean
        get() = status == SessionStatus.ACTIVE && System.currentTimeMillis() >= endTimeMillis

    val remainingMillis: Long
        get() {
            if (status != SessionStatus.ACTIVE) return 0L
            return maxOf(0L, endTimeMillis - System.currentTimeMillis())
        }

    val remainingSeconds: Long
        get() = remainingMillis / 1000L

    val progress: Float
        get() {
            if (totalDurationSeconds <= 0L) return 0f
            val elapsed = totalDurationSeconds - remainingSeconds
            return (elapsed.toFloat() / totalDurationSeconds.toFloat()).coerceIn(0f, 1f)
        }

    fun isPackageBlocked(packageName: String): Boolean {
        if (!isActive) return false
        return blockedPackages.contains(packageName)
    }
}
