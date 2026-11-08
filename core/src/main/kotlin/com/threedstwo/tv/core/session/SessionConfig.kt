package com.threedstwo.tv.core.session

import com.threedstwo.tv.core.ntr.NtrConstants
import com.threedstwo.tv.core.ntr.RemotePlayRequest

data class SessionConfig(
    /** Console IPv4. Required in ACTIVE mode; optional in PASSIVE mode (learned from the first packet). */
    val consoleIp: String?,
    val mode: ConnectMode = ConnectMode.ACTIVE,
    val request: RemotePlayRequest = RemotePlayRequest(),
    val controlPort: Int = NtrConstants.CONTROL_PORT,
    val connectTimeoutMs: Int = 2500,
    /** No video for this long while streaming triggers a reconnection. */
    val videoTimeoutMs: Long = 3000,
    /** After the 901 command, wait this long before re-sending it once. */
    val firstFrameRetryMs: Long = 3000,
    /** After the 901 command, give up this attempt after this long without video. */
    val firstFrameTimeoutMs: Long = 8000,
    val maxReconnectAttempts: Int = 8,
    val heartbeatIntervalMs: Long = 1000,
    /** Bind address for the UDP socket (null = any). Tests use loopback. */
    val bindAddress: String? = null,
) {
    val viewerPort: Int get() = request.viewerPort

    /** Exponential backoff for attempt n (1-based): 1 s, 2 s, 4 s, 8 s, 8 s... */
    fun backoffMs(attempt: Int): Long = (1000L shl (attempt - 1).coerceIn(0, 3))
}
