package com.gnatok.translator

import com.meta.wearable.dat.core.types.DeviceSessionError

/** Camera.stop() may already detach on DAT 1.0; only an absent capability is success. */
internal fun cameraDetachFailure(error: DeviceSessionError): IllegalStateException? =
    if (error == DeviceSessionError.CAPABILITY_NOT_FOUND) null
    else IllegalStateException("Could not detach glasses camera (${error.name})")
