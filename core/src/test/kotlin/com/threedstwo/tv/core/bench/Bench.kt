package com.threedstwo.tv.core.bench

import org.junit.Assume

/**
 * Tiny reproducible micro-benchmark harness (no external dependency). Runs `batches` batches of
 * `opsPerBatch` operations after a warm-up and reports ns/op percentiles across batches.
 * JVM numbers are for before/after comparison only; they are NOT Chromecast timings.
 */
object Bench {
    fun enabled() = System.getProperty("bench") == "true"
    fun assumeEnabled() = Assume.assumeTrue("benchmarks run only with -Pbench", enabled())

    var sink = 0L // defeats dead-code elimination

    data class Result(val name: String, val p50: Double, val p95: Double, val min: Double) {
        override fun toString() = "BENCH %-38s p50 %10.1f ns/op  p95 %10.1f  min %10.1f".format(name, p50, p95, min)
    }

    fun run(name: String, opsPerBatch: Int, batches: Int = 30, warmupBatches: Int = 15, op: (Int) -> Long): Result {
        repeat(warmupBatches) { for (i in 0 until opsPerBatch) sink += op(i) }
        val per = DoubleArray(batches)
        for (b in 0 until batches) {
            val t0 = System.nanoTime()
            for (i in 0 until opsPerBatch) sink += op(i)
            per[b] = (System.nanoTime() - t0).toDouble() / opsPerBatch
        }
        per.sort()
        return Result(name, per[batches / 2], per[(batches * 95) / 100], per[0]).also { println(it) }
    }
}
