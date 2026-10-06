package com.gnatok.translator

/** Release every resource without replacing the operation's original failure or cancellation. */
internal suspend fun <T> withCaptureCleanup(
    stopCapture: suspend () -> Unit,
    closeDetector: () -> Unit,
    capture: suspend () -> T
): T {
    var primary: Throwable? = null
    try {
        return capture()
    } catch (error: Throwable) {
        primary = error
        throw error
    } finally {
        var cleanupFailure: Throwable? = null
        try { stopCapture() } catch (error: Throwable) { cleanupFailure = error }
        try { closeDetector() } catch (error: Throwable) {
            if (cleanupFailure == null) cleanupFailure = error
            else if (cleanupFailure !== error) cleanupFailure.addSuppressed(error)
        }
        cleanupFailure?.let { error ->
            val original = primary ?: throw error
            if (original !== error) original.addSuppressed(error)
        }
    }
}
