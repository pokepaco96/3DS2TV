package com.threedstwo.tv.mock

import com.threedstwo.tv.core.input.InputFrame
import com.threedstwo.tv.core.ntr.NtrConstants
import java.net.InetAddress

private fun usage() {
    println(
        """
        mock-3ds-sender — simulated NTR-HR console for 3DS2TV development (NOT a real 3DS)

        Server mode (default): behaves like NTR-HR; point 3DS2TV at this PC's IP.
          --control-port N   TCP port for NTR commands (default 8000)
          --input-port N     UDP port to receive Luma3DS input redirection (default 4950, -1 disables)
          --fps N            frames per second per stream slot (default 60)
          --loss P           packet loss probability 0..1 (default 0)
          --reorder          shuffle packets inside each frame
          --duplicate        send every video packet twice
          --jitter-ms N      random 0..N ms delay before each frame
          --corrupt P        probability of corrupting a byte of a video packet
          --tcp-delay-ms N   delay every TCP reply by N ms
          --refuse-tcp       close every TCP connection (console without port 8000)
          --seed N           deterministic random seed (default 42)

        Push mode (like Remote Play started from the console menu):
          --push IP[:PORT]   stream directly to a viewer without waiting for TCP (default port 8001)
          --audio            also send DSP-style audio (440 Hz sine)
        """.trimIndent(),
    )
}

fun main(args: Array<String>) {
    var controlPort = NtrConstants.CONTROL_PORT
    var inputPort = InputFrame.PORT
    var fps = 60
    var loss = 0.0
    var reorder = false
    var push: String? = null
    var audio = false
    var duplicate = false
    var jitter = 0
    var corrupt = 0.0
    var tcpDelay = 0L
    var refuseTcp = false
    var seed = 42L
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--control-port" -> controlPort = args[++i].toInt()
            "--input-port" -> inputPort = args[++i].toInt()
            "--fps" -> fps = args[++i].toInt()
            "--loss" -> loss = args[++i].toDouble()
            "--reorder" -> reorder = true
            "--push" -> push = args[++i]
            "--audio" -> audio = true
            "--duplicate" -> duplicate = true
            "--jitter-ms" -> jitter = args[++i].toInt()
            "--corrupt" -> corrupt = args[++i].toDouble()
            "--tcp-delay-ms" -> tcpDelay = args[++i].toLong()
            "--refuse-tcp" -> refuseTcp = true
            "--seed" -> seed = args[++i].toLong()
            "-h", "--help" -> { usage(); return }
            else -> { println("unknown option ${args[i]}"); usage(); return }
        }
        i++
    }
    val console = FakeNtrConsole(controlPort, inputPort, fps = fps, lossProbability = loss, reorder = reorder, log = { println(it) }, seed = seed)
    console.duplicatePackets = duplicate
    console.jitterMs = jitter
    console.corruptProbability = corrupt
    console.tcpReplyDelayMs = tcpDelay
    console.refuseTcp = refuseTcp
    console.start()
    push?.let {
        val parts = it.split(':')
        val port = parts.getOrNull(1)?.toInt() ?: NtrConstants.DEFAULT_VIEWER_PORT
        console.streamTo(InetAddress.getByName(parts[0]), port, audio)
        println("Pushing video to ${parts[0]}:$port")
    }
    println("Running. Ctrl+C to stop.")
    Runtime.getRuntime().addShutdownHook(Thread { console.stop() })
    while (true) {
        Thread.sleep(5000)
        println("frames sent: ${console.framesSent.get()}, audio packets: ${console.audioPacketsSent.get()}, heartbeats: ${console.heartbeats.get()}, input packets: ${console.inputFrames.size}")
    }
}
