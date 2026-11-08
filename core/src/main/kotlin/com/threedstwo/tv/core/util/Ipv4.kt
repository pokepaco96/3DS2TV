package com.threedstwo.tv.core.util

/** Minimal IPv4 parsing/validation without touching DNS (no lookups, no network). */
object Ipv4 {
    /** Returns the 4 octets or null if [text] is not a dotted-quad IPv4 literal. */
    fun parse(text: String): IntArray? {
        val parts = text.trim().split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (i in 0 until 4) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            if (p.length > 1 && p[0] == '0') return null
            val v = p.toInt()
            if (v > 255) return null
            out[i] = v
        }
        return out
    }

    fun isValidHostAddress(text: String): Boolean {
        val o = parse(text) ?: return false
        if (o[0] == 0 || o[0] == 127 || o[0] >= 224) return false
        if (o.all { it == 255 }) return false
        if (o[3] == 0 || o[3] == 255) return false
        return true
    }

    fun isPrivate(text: String): Boolean {
        val o = parse(text) ?: return false
        return o[0] == 10 ||
            (o[0] == 172 && o[1] in 16..31) ||
            (o[0] == 192 && o[1] == 168) ||
            (o[0] == 169 && o[1] == 254)
    }

    fun toInt(o: IntArray): Int = (o[0] shl 24) or (o[1] shl 16) or (o[2] shl 8) or o[3]

    /** True if both addresses are in the same network for the given prefix length. */
    fun sameSubnet(a: String, b: String, prefixLength: Int): Boolean {
        val oa = parse(a) ?: return false
        val ob = parse(b) ?: return false
        if (prefixLength !in 0..32) return false
        val mask = if (prefixLength == 0) 0 else (-1 shl (32 - prefixLength))
        return (toInt(oa) and mask) == (toInt(ob) and mask)
    }
}
