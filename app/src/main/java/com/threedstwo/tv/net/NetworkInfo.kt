package com.threedstwo.tv.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/** Local network facts used for diagnostics (no scanning, no external requests). */
object NetworkInfo {
    data class Local(val ip: String, val prefixLength: Int, val transport: String)

    fun local(ctx: Context): Local? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val net = cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(net) ?: return null
        val caps = cm.getNetworkCapabilities(net)
        val transport = when {
            caps == null -> "desconocida"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            else -> "otra"
        }
        val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        return Local(la.address.hostAddress ?: return null, la.prefixLength, transport)
    }
}
