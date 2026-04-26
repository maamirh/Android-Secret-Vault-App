package com.securevaultoffline.app

/**
 * In-memory session flag cleared when the app process is no longer in the foreground
 * ([ProcessLifecycleOwner]). Cryptographic keys remain in Android Keystore; UI and
 * decrypted preview files are dropped on background.
 */
object SessionGate {
    @Volatile
    var isUnlocked: Boolean = false
        private set

    @Volatile
    private var externalFlowGraceUntilMs: Long = 0L

    fun unlock() {
        isUnlocked = true
    }

    /**
     * Prevent immediate autolock while a trusted system UI is on top
     * (file picker / create document).
     */
    fun allowExternalFlowFor(ms: Long) {
        val now = System.currentTimeMillis()
        val newUntil = now + ms.coerceAtLeast(0L)
        if (newUntil > externalFlowGraceUntilMs) {
            externalFlowGraceUntilMs = newUntil
        }
    }

    fun shouldAutolockNow(): Boolean {
        val now = System.currentTimeMillis()
        return now >= externalFlowGraceUntilMs
    }

    fun clear() {
        isUnlocked = false
        externalFlowGraceUntilMs = 0L
    }
}
