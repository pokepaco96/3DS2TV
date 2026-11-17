package com.threedstwo.tv.core.lab

import com.threedstwo.tv.core.layout.Arrangement
import com.threedstwo.tv.core.layout.ViewMode
import com.threedstwo.tv.core.session.ConnectMode
import com.threedstwo.tv.core.session.StreamStats
import com.threedstwo.tv.core.settings.AppSettings
import com.threedstwo.tv.core.settings.PerformanceProfile
import com.threedstwo.tv.core.util.Ipv4

/**
 * Hardware-lab automation (DEBUG BUILDS ONLY, see app/src/debug): settings overrides received from
 * `adb shell am broadcast ... --es key value` and a JSON export of the live metrics.
 * Pure logic so it is unit-tested on the JVM; nothing here runs per frame.
 */
object LabControl {
    const val ACTION_START = "com.threedstwo.tv.debug.START"
    const val ACTION_STOP = "com.threedstwo.tv.debug.STOP"
    const val ACTION_EXPORT = "com.threedstwo.tv.debug.EXPORT_METRICS"

    /** Keys accepted by START. Unknown keys / invalid values are reported, never applied. */
    val KEYS = listOf("ip", "mode", "profile", "quality", "qos", "audio", "hud", "input", "view", "layout", "adaptive")

    data class Result(val settings: AppSettings, val errors: List<String>)

    fun applyOverrides(base: AppSettings, extras: Map<String, String>): Result {
        var s = base
        val errors = mutableListOf<String>()
        fun bool(k: String, v: String) = when (v.lowercase()) {
            "true", "1", "on", "yes" -> true
            "false", "0", "off", "no" -> false
            else -> { errors += "$k: '$v' no es booleano"; null }
        }
        fun int(k: String, v: String, r: IntRange) = v.toIntOrNull()?.takeIf { it in r } ?: run { errors += "$k: '$v' fuera de $r"; null }
        fun <E : Enum<E>> pick(k: String, v: String, values: Array<E>): E? =
            values.firstOrNull { it.name.equals(v, ignoreCase = true) } ?: run {
                errors += "$k: '$v' no es uno de ${values.joinToString { it.name }}"; null
            }
        // profile first, so explicit quality/qos override it
        extras["profile"]?.let { v -> pick("profile", v, PerformanceProfile.entries.toTypedArray())?.let { s = it.applyTo(s) } }
        for ((k, v) in extras) {
            when (k) {
                "profile" -> {}
                "ip" -> if (Ipv4.isValidHostAddress(v)) s = s.copy(consoleIp = v) else errors += "ip: '$v' no es una IPv4 válida"
                "mode" -> pick(k, v, ConnectMode.entries.toTypedArray())?.let { s = s.copy(connectMode = it) }
                "quality" -> int(k, v, 10..100)?.let { s = s.copy(jpegQuality = it, profile = PerformanceProfile.CUSTOM) }
                "qos" -> int(k, v, 4..20)?.let { s = s.copy(qosMbps = it, profile = PerformanceProfile.CUSTOM) }
                "audio" -> bool(k, v)?.let { s = s.copy(audioEnabled = it) }
                "hud" -> bool(k, v)?.let { s = s.copy(showHud = it) }
                "input" -> bool(k, v)?.let { s = s.copy(inputRedirection = it) }
                "adaptive" -> bool(k, v)?.let { s = s.copy(adaptiveQuality = it) }
                "view" -> pick(k, v, ViewMode.entries.toTypedArray())?.let { s = s.copy(layout = s.layout.copy(viewMode = it)) }
                "layout" -> pick(k, v, Arrangement.entries.toTypedArray())?.let { s = s.copy(layout = s.layout.copy(arrangement = it)) }
                else -> errors += "clave desconocida '$k' (válidas: ${KEYS.joinToString()})"
            }
        }
        return Result(s, errors)
    }

    private fun esc(v: String) = buildString {
        for (c in v) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }

    private fun num(v: Double?) = if (v == null || v.isNaN() || v.isInfinite()) "null" else "%.3f".format(java.util.Locale.US, v)

    /** Flat JSON object (no dependency) consumed by the PowerShell scripts in tools/hardware-lab. */
    fun toJson(st: StreamStats, meta: Map<String, String>, counters: Map<String, Long>): String = buildString {
        append('{')
        val parts = mutableListOf<String>()
        meta.forEach { (k, v) -> parts += "\"${esc(k)}\":\"${esc(v)}\"" }
        parts += "\"state\":\"${st.state.name}\""
        parts += "\"transport\":\"${esc(st.transport)}\""
        parts += "\"fps_rx_top\":${num(st.receivedFpsTop)}"
        parts += "\"fps_rx_bottom\":${num(st.receivedFpsBottom)}"
        parts += "\"fps_presented\":${num(st.displayedFps)}"
        parts += "\"kbps\":${num(st.kbps)}"
        parts += "\"frames_incomplete\":${st.framesIncomplete}"
        parts += "\"frames_dropped_before_decode\":${st.framesDroppedBeforeDecode}"
        parts += "\"frames_decode_errors\":${st.framesDecodeErrors}"
        parts += "\"invalid_packets\":${st.invalidPackets}"
        parts += "\"stale_packets\":${st.stalePackets}"
        parts += "\"decode_p50_ms\":${num(st.decodeMsAvg)}"
        parts += "\"decode_p95_ms\":${num(st.decodeP95Ms)}"
        parts += "\"draw_p95_ms\":${num(st.drawP95Ms)}"
        parts += "\"rx_to_display_p50_ms\":${num(st.receiveToDisplayMsAvg)}"
        parts += "\"rx_to_display_p95_ms\":${num(st.receiveToDisplayP95Ms)}"
        parts += "\"input_send_p50_ms\":${num(st.inputSendP50Ms)}"
        parts += "\"input_send_p95_ms\":${num(st.inputSendP95Ms)}"
        parts += "\"ntr_request\":\"${esc(st.requestState)}\""
        parts += "\"audio\":\"${esc(st.audio)}\""
        parts += "\"input\":\"${esc(st.input)}\""
        parts += "\"controller\":\"${esc(st.controller)}\""
        counters.forEach { (k, v) -> parts += "\"${esc(k)}\":$v" }
        append(parts.joinToString(","))
        append('}')
    }
}
