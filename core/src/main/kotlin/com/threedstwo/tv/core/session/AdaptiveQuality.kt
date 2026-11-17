package com.threedstwo.tv.core.session

/**
 * Adapts the JPEG quality sent to NTR-HR to the measured frame loss, with hysteresis so it never
 * oscillates (each change makes NTR-HR restart its encoder threads, which costs ~2 frames).
 *
 * Fed once per second with cumulative counters. Decision rules:
 *  - window ignored if fewer than [minFrames] frames were seen (idle menus, pauses);
 *  - loss > [highLoss] in [downWindows] consecutive windows → quality −[downStep] (not below [floor]);
 *  - loss < [lowLoss] in [upWindows] consecutive windows → quality +[upStep] (not above the ceiling);
 *  - after any change, [cooldownWindows] windows without decisions (let the encoder settle).
 */
class AdaptiveQuality(
    ceiling: Int,
    val floor: Int = 50,
    private val highLoss: Double = 0.12,
    private val lowLoss: Double = 0.03,
    private val downWindows: Int = 2,
    private val upWindows: Int = 8,
    private val downStep: Int = 10,
    private val upStep: Int = 5,
    private val cooldownWindows: Int = 3,
    private val minFrames: Long = 10,
) {
    var ceiling: Int = ceiling
        set(v) {
            field = v
            if (quality > v) quality = v
        }
    var quality: Int = ceiling
        private set
    var lastLossRatio: Double = 0.0
        private set

    private var lastCompleted = -1L
    private var lastIncomplete = 0L
    private var badRun = 0
    private var goodRun = 0
    private var cooldown = 0

    fun reset(newQuality: Int = ceiling) {
        quality = newQuality.coerceIn(floor, ceiling)
        lastCompleted = -1
        badRun = 0
        goodRun = 0
        cooldown = 0
    }

    /** @return the new quality if it changed, else null. */
    fun onWindow(framesCompleted: Long, framesIncomplete: Long): Int? {
        if (lastCompleted < 0 || framesCompleted < lastCompleted || framesIncomplete < lastIncomplete) {
            lastCompleted = framesCompleted
            lastIncomplete = framesIncomplete
            return null
        }
        val ok = framesCompleted - lastCompleted
        val bad = framesIncomplete - lastIncomplete
        lastCompleted = framesCompleted
        lastIncomplete = framesIncomplete
        if (ok + bad < minFrames) return null
        val loss = bad.toDouble() / (ok + bad)
        lastLossRatio = loss
        if (cooldown > 0) {
            cooldown--
            return null
        }
        when {
            loss > highLoss -> { badRun++; goodRun = 0 }
            loss < lowLoss -> { goodRun++; badRun = 0 }
            else -> { badRun = 0; goodRun = 0 }
        }
        val next = when {
            badRun >= downWindows && quality > floor -> (quality - downStep).coerceAtLeast(floor)
            goodRun >= upWindows && quality < ceiling -> (quality + upStep).coerceAtMost(ceiling)
            else -> return null
        }
        quality = next
        badRun = 0
        goodRun = 0
        cooldown = cooldownWindows
        return next
    }
}
