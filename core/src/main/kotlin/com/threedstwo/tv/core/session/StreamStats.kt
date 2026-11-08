package com.threedstwo.tv.core.session

/** Converts cumulative counters into per-second rates between two snapshots. */
class RateMeter {
    private var lastValue = 0L
    private var lastNanos = 0L
    var rate = 0.0
        private set

    fun update(value: Long, nowNanos: Long): Double {
        if (lastNanos != 0L) {
            val dt = (nowNanos - lastNanos) / 1e9
            if (dt > 0) rate = (value - lastValue).coerceAtLeast(0) / dt
        }
        lastValue = value
        lastNanos = nowNanos
        return rate
    }
}

/** Snapshot shown in the diagnostics HUD. Only measured values; null = not measurable / not applicable. */
data class StreamStats(
    val state: ConnectionState,
    val transport: String,
    val receivedFpsTop: Double,
    val receivedFpsBottom: Double,
    val displayedFps: Double,
    val kbps: Double,
    val framesIncomplete: Long,
    val framesDroppedBeforeDecode: Long,
    val framesDecodeErrors: Long,
    val invalidPackets: Long,
    val decodeMsAvg: Double?,
    /** Time from last packet of a frame to its presentation on our side (does not include console/Wi-Fi time). */
    val receiveToDisplayMsAvg: Double?,
    val audio: String,
    val input: String,
    val controller: String,
)

/** Fixed-size running average without allocation. */
class RunningAverage(private val size: Int = 60) {
    private val values = DoubleArray(size)
    private var count = 0
    private var index = 0

    @Synchronized fun add(v: Double) {
        values[index] = v
        index = (index + 1) % size
        if (count < size) count++
    }

    @Synchronized fun average(): Double? = if (count == 0) null else values.take(count).average()

    @Synchronized fun clear() {
        count = 0
        index = 0
    }
}
