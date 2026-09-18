package com.example.appblock.model

/**
 * Status of the lock lifecycle.
 */
enum class LockStatus {
    IDLE,
    ACTIVE,
    COMPLETED,
    FAILED
}

/**
 * Immutable representation of persistent lock state.
 */
data class LockState(
    val status: LockStatus = LockStatus.IDLE,
    val targetPackage: String = "",
    val targetAppName: String = "",
    val startTimeMs: Long = 0L,
    val expirationTimeMs: Long = 0L,
    val durationSeconds: Long = 0L
) {
    val isActive: Boolean
        get() = status == LockStatus.ACTIVE

    fun getRemainingSeconds(nowMs: Long = System.currentTimeMillis()): Long {
        if (!isActive) return 0L
        val remainingMs = expirationTimeMs - nowMs
        return if (remainingMs > 0) (remainingMs + 999) / 1000 else 0L
    }

    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean {
        return isActive && nowMs >= expirationTimeMs
    }
}
