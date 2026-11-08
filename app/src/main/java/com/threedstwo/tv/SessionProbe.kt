package com.threedstwo.tv

import com.threedstwo.tv.core.session.ConnectionState

/** Read-only snapshot of the running session for automated (instrumented) tests and diagnostics. */
object SessionProbe {
    @Volatile var state: ConnectionState = ConnectionState.DISCONNECTED
    @Volatile var presentedFrames = 0L
    @Volatile var decodedFrames = 0L
    @Volatile var decodeErrors = 0L
    @Volatile var surfaceWidth = 0
}
