package com.gnatok.translator

import com.meta.wearable.dat.core.types.DeviceSessionError
import org.junit.Assert.*
import org.junit.Test

class CameraDetachTest {
    @Test fun stoppingAnAlreadyDetachedCameraAllowsTheNextTurn() {
        // Regression: physical glasses report this after Camera.stop() auto-detaches.
        assertNull(cameraDetachFailure(DeviceSessionError.CAPABILITY_NOT_FOUND))
    }

    @Test fun actualDetachFailuresRemainVisible() {
        for (error in DeviceSessionError.values()) {
            if (error == DeviceSessionError.CAPABILITY_NOT_FOUND) continue
            val failure = cameraDetachFailure(error)
            assertNotNull("Must not hide $error", failure)
            assertTrue(failure!!.message!!.contains(error.name))
        }
    }
}
