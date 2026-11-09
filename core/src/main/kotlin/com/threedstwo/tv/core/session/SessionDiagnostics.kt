package com.threedstwo.tv.core.session

/**
 * Counters for each stage of the pipeline, so a failure can be located precisely:
 * TCP connect → Remote Play command → UDP from the console → complete JPEG frames → presented image.
 * Written by the session threads, read by the UI (approximate reads are fine).
 */
class SessionDiagnostics {
    @Volatile var tcpConnects = 0L
    @Volatile var tcpFailures = 0L
    @Volatile var tcpReplies = 0L
    @Volatile var tcpProtocolErrors = 0L
    @Volatile var remotePlayCommands = 0L
    @Volatile var udpPacketsFromConsole = 0L
    @Volatile var videoPackets = 0L
    @Volatile var foreignNtrPackets = 0L
    @Volatile var lastForeignSource: String? = null
        private set
    @Volatile var lastTcpError: String? = null

    fun noteForeign(ip: String) {
        foreignNtrPackets++
        lastForeignSource = ip
    }

    fun reset() {
        tcpConnects = 0; tcpFailures = 0; tcpReplies = 0; tcpProtocolErrors = 0
        remotePlayCommands = 0; udpPacketsFromConsole = 0; videoPackets = 0; foreignNtrPackets = 0
        lastForeignSource = null
        lastTcpError = null
    }
}

/** One line of the connection checklist shown to the user. */
data class CheckItem(val label: String, val status: Status, val hint: String? = null) {
    enum class Status { OK, PENDING, FAILED, NOT_APPLICABLE }
}

/**
 * Builds the human-readable checklist from measured counters. It NEVER reports the image as working
 * unless complete frames were received recently AND presented.
 */
object ConnectionChecklist {
    data class Inputs(
        val mode: ConnectMode,
        val state: ConnectionState,
        val diag: SessionDiagnostics,
        val framesCompleted: Long,
        val recentFrames: Boolean,
        val framesPresented: Long,
        val inputRedirectionEnabled: Boolean,
        val inputRedirectionTarget: String?,
        val unsupported: String?,
    )

    fun evaluate(i: Inputs): List<CheckItem> {
        val d = i.diag
        val ok = CheckItem.Status.OK
        val pending = CheckItem.Status.PENDING
        val failed = CheckItem.Status.FAILED
        val na = CheckItem.Status.NOT_APPLICABLE
        val items = mutableListOf<CheckItem>()
        if (i.mode == ConnectMode.ACTIVE) {
            items += when {
                d.tcpConnects > 0 -> CheckItem("Conexión TCP 8000 con NTR-HR", ok)
                d.tcpFailures > 0 && i.framesCompleted > 0 -> CheckItem("Conexión TCP 8000 con NTR-HR", failed,
                    "La imagen llega porque la consola ya está emitiendo, pero 3DS2TV no puede enviarle órdenes" +
                        (d.lastTcpError?.let { " ($it)" } ?: "") +
                        ": la calidad, el QoS y el audio de 3DS2TV NO se aplican. Cámbialos en la 3DS (X+Y → Remote Play) o reinicia Remote Play/NTR-HR.")
                d.tcpFailures > 0 -> CheckItem("Conexión TCP 8000 con NTR-HR", failed,
                    "La consola no acepta la conexión" + (d.lastTcpError?.let { " ($it)" } ?: "") +
                        ": NTR-HR no está arrancado, la IP es otra o hay aislamiento Wi-Fi.")
                else -> CheckItem("Conexión TCP 8000 con NTR-HR", pending)
            }
            items += if (d.remotePlayCommands > 0) {
                CheckItem("Orden Remote Play (JPEG/UDP) enviada", ok, "Enviada ${d.remotePlayCommands} vez/veces")
            } else {
                CheckItem("Orden Remote Play (JPEG/UDP) enviada", pending)
            }
        } else {
            items += CheckItem("Conexión TCP (no se usa en modo pasivo)", na)
        }
        items += when {
            d.udpPacketsFromConsole > 0 -> CheckItem("Paquetes UDP de la consola", ok, "${d.udpPacketsFromConsole} recibidos")
            d.foreignNtrPackets > 0 -> CheckItem("Paquetes UDP de la consola", failed,
                "Llegan paquetes NTR desde ${d.lastForeignSource}, no desde la IP configurada.")
            else -> CheckItem("Paquetes UDP de la consola", pending,
                if (i.mode == ConnectMode.PASSIVE) "En NTR-HR (X+Y → Remote Play) pon Viewer IP = este dispositivo y JPEG UDP." else null)
        }
        items += when {
            i.unsupported != null -> CheckItem("Frames JPEG completos", failed, i.unsupported)
            i.framesCompleted > 0 && i.recentFrames -> CheckItem("Frames JPEG completos", ok, "${i.framesCompleted} completos")
            i.framesCompleted > 0 -> CheckItem("Frames JPEG completos", failed, "Hace más de unos segundos que no llega ningún frame completo")
            d.videoPackets > 0 -> CheckItem("Frames JPEG completos", pending, "Llegan paquetes pero aún ningún frame completo (pérdidas)")
            else -> CheckItem("Frames JPEG completos", pending)
        }
        items += if (i.framesPresented > 0 && i.recentFrames) {
            CheckItem("Imagen presentada en pantalla", ok)
        } else {
            CheckItem("Imagen presentada en pantalla", pending)
        }
        items += when {
            !i.inputRedirectionEnabled -> CheckItem("Input Redirection (mandos)", na, "Desactivado en 3DS2TV")
            i.inputRedirectionTarget == null -> CheckItem("Input Redirection (mandos)", pending, "Falta la IP de la consola")
            else -> CheckItem("Input Redirection (mandos)", ok,
                "Enviando a ${i.inputRedirectionTarget}:4950. UDP sin confirmación: actívalo en Rosalina para que tenga efecto.")
        }
        return items
    }

    fun format(items: List<CheckItem>): String = items.joinToString("\n") {
        val mark = when (it.status) {
            CheckItem.Status.OK -> "✔"
            CheckItem.Status.PENDING -> "…"
            CheckItem.Status.FAILED -> "✖"
            CheckItem.Status.NOT_APPLICABLE -> "–"
        }
        "$mark ${it.label}" + (it.hint?.let { h -> "\n    $h" } ?: "")
    }
}
