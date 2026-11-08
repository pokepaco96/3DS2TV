package com.threedstwo.tv.core.session

enum class ConnectionState(val label: String) {
    DISCONNECTED("Desconectado"),
    /** Passive mode: waiting for the console to start Remote Play towards us. */
    SEARCHING("Esperando a la consola"),
    CONNECTING("Conectando"),
    /** TCP command accepted, waiting for the first UDP video frame. */
    CONNECTED("Conectado, esperando imagen"),
    STREAMING("Recibiendo imagen"),
    RECONNECTING("Reconectando"),
    ERROR("Error"),
}

enum class ConnectMode {
    /** Connect to NTR-HR TCP 8000 and send the Remote Play command (recommended). */
    ACTIVE,
    /** Only listen on UDP: Remote Play is started from the NTR-HR console menu with Viewer IP = this device. */
    PASSIVE,
}
