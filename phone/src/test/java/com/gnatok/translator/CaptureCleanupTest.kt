package com.gnatok.translator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CaptureCleanupTest {
    @Test fun successfulCaptureReleasesBothResources() = runBlocking {
        val events = mutableListOf<String>()
        val result = withCaptureCleanup({ events.add("stop"); Unit }, { events.add("close"); Unit }) {
            events.add("capture"); "samples"
        }
        assertEquals("samples", result)
        assertEquals(listOf("capture", "stop", "close"), events)
    }

    @Test fun captureErrorSurvivesTwoCleanupFailures() = runBlocking {
        val original = IllegalStateException("No PCM received")
        val stop = IllegalStateException("Detach failure")
        val close = IllegalStateException("Detector close failure")
        val thrown = runCatching {
            withCaptureCleanup({ throw stop }, { throw close }) { throw original }
        }.exceptionOrNull()
        assertSame(original, thrown)
        assertSame(stop, original.suppressed.single())
        assertSame(close, stop.suppressed.single())
    }

    @Test fun detectorStillClosesWhenStoppingFails() = runBlocking {
        val stop = IllegalStateException("Real teardown failure")
        var detectorClosed = false
        val thrown = runCatching {
            withCaptureCleanup({ throw stop }, { detectorClosed = true }) { "samples" }
        }.exceptionOrNull()
        assertSame(stop, thrown)
        assertTrue(detectorClosed)
    }

    @Test fun cancellationRemainsCancellationDespiteCleanupError() = runBlocking {
        val cancel = CancellationException("User stopped")
        var detectorClosed = false
        val thrown = runCatching {
            withCaptureCleanup({ throw IllegalStateException("Detach failure") }, { detectorClosed = true }) { throw cancel }
        }.exceptionOrNull()
        assertSame(cancel, thrown)
        assertTrue(detectorClosed)
        assertEquals(1, cancel.suppressed.size)
    }
}
