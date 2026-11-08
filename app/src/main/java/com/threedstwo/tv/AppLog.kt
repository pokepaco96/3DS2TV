package com.threedstwo.tv

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory ring buffer log (exportable from Diagnostics) mirrored to Logcat with tag "3DS2TV".
 * Holds only technical messages: IPs of the local console/devices, states, errors. No accounts or ids.
 */
object AppLog {
    private const val TAG = "3DS2TV"
    private const val MAX = 600
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized private fun add(level: String, msg: String) {
        lines.addLast("${fmt.format(Date())} $level $msg")
        while (lines.size > MAX) lines.removeFirst()
    }

    fun i(msg: String) { Log.i(TAG, msg); add("I", msg) }
    fun w(msg: String) { Log.w(TAG, msg); add("W", msg) }
    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        add("E", if (t != null) "$msg: ${t.javaClass.simpleName}: ${t.message}" else msg)
    }

    @Synchronized fun snapshot(): List<String> = lines.toList()
}
