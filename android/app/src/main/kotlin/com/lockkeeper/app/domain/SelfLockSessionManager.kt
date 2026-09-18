package com.lockkeeper.app.domain

class SelfLockSessionManager {

    @Volatile
    var isSessionActive: Boolean = false
        private set

    @Volatile
    var lastBackgroundTimestamp: Long = 0L
        private set

    fun grantSession() {
        isSessionActive = true
        lastBackgroundTimestamp = 0L
    }

    fun invalidateSession() {
        isSessionActive = false
        lastBackgroundTimestamp = 0L
    }

    fun recordBackgrounded(timestamp: Long = System.currentTimeMillis()) {
        if (isSessionActive) {
            lastBackgroundTimestamp = timestamp
        }
    }

    fun isAuthRequired(
        selfLockEnabled: Boolean,
        onboardingComplete: Boolean,
        timeoutSeconds: Int,
        lockoutUntil: Long?,
        currentTime: Long = System.currentTimeMillis()
    ): Boolean {
        // 1. If self-lock is disabled or onboarding is not complete, no auth required
        if (!selfLockEnabled || !onboardingComplete) {
            return false
        }

        // 2. If PIN lockout is active, auth / lockout gate is required
        if (lockoutUntil != null && lockoutUntil > currentTime) {
            return true
        }

        // 3. If there is an active session in memory, check background timeout
        if (isSessionActive) {
            if (lastBackgroundTimestamp > 0L) {
                val timeoutMs = timeoutSeconds * 1000L
                if (currentTime - lastBackgroundTimestamp > timeoutMs) {
                    // Timeout expired: session invalidated
                    isSessionActive = false
                    lastBackgroundTimestamp = 0L
                    return true
                } else {
                    // Resumed within grace period: session remains active, reset background timestamp
                    lastBackgroundTimestamp = 0L
                    return false
                }
            }
            return false
        }

        // 4. Default: no active session in memory -> auth required
        return true
    }
}
