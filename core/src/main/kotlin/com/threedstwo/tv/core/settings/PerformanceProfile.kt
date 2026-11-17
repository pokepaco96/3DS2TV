package com.threedstwo.tv.core.settings

/**
 * Presets of parameters that NTR-HR really accepts (cmd 901: JPEG quality, QoS) plus local buffering.
 *
 * Rationale: a frame of N packets arrives complete with probability (1-p)^N, so a lower JPEG quality
 * (fewer packets per frame) loses fewer frames on the 3DS's 2.4 GHz Wi-Fi and also encodes faster on
 * the console (NTR-HR README: quality 75 → 55–75 fps, 90 → 40–60 fps on New 2DS).
 */
enum class PerformanceProfile(
    val label: String,
    val jpegQuality: Int,
    val qosMbps: Int,
    /** Max audio queued in AudioTrack before old audio is dropped. */
    val audioMaxLatencyMs: Int,
) {
    ULTRA_LOW_LATENCY("Ultra baja latencia (recomendado para jugar)", 70, 10, 60),
    BALANCED("Equilibrado", 80, 12, 100),
    QUALITY("Calidad", 90, 16, 150),
    CUSTOM("Personalizado", -1, -1, 100),
    ;

    fun applyTo(s: AppSettings): AppSettings =
        if (this == CUSTOM) s.copy(profile = CUSTOM) else s.copy(profile = this, jpegQuality = jpegQuality, qosMbps = qosMbps)
}
