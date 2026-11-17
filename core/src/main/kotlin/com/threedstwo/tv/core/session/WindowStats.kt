package com.threedstwo.tv.core.session

/**
 * Bounded sliding window of the last [capacity] samples with p50/p95/p99.
 * add() is O(1) with no allocation (hot path); percentiles() sorts a preallocated scratch copy and
 * is meant for the HUD (a few times per second), never per frame.
 */
class WindowStats(private val capacity: Int = 256) {
    data class Percentiles(val count: Int, val p50: Double, val p95: Double, val p99: Double, val max: Double)

    private val values = DoubleArray(capacity)
    private val scratch = DoubleArray(capacity)
    private var count = 0
    private var index = 0

    @Synchronized fun add(v: Double) {
        values[index] = v
        index = (index + 1) % capacity
        if (count < capacity) count++
    }

    @Synchronized fun clear() {
        count = 0
        index = 0
    }

    @Synchronized fun percentiles(): Percentiles? {
        if (count == 0) return null
        System.arraycopy(values, 0, scratch, 0, count)
        java.util.Arrays.sort(scratch, 0, count)
        fun q(p: Double) = scratch[((count - 1) * p).toInt().coerceIn(0, count - 1)]
        return Percentiles(count, q(0.50), q(0.95), q(0.99), scratch[count - 1])
    }

    @Synchronized fun average(): Double? = if (count == 0) null else values.take(count).average()
}
