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
            "-h", "--help" -> { usage(); return }
            else -> { println("unknown option ${args[i]}"); usage(); return }
        }
        i++
    }
    val console = FakeNtrConsole(controlPort, inputPort, fps = fps, lossProbability = loss, reorder = reorder, log = { println(it) })
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
