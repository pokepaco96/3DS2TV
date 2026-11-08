package com.threedstwo.tv.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.threedstwo.tv.core.util.Ipv4
import java.net.Inet4Address

/** Local network facts used for diagnostics (no scanning, no external requests). */
object NetworkInfo {
    data class Local(val ip: String, val prefixLength: Int, val transport: String, val iface: String?)

    /**
     * The IPv4 LAN address the console must send video to. Prefers Wi-Fi/Ethernet networks and
     * private addresses; never returns a VPN/tunnel address (a VPN would otherwise be the "active"
     * network and show an IP the 3DS cannot reach).
     */
    fun local(ctx: Context): Local? = candidates(ctx).firstOrNull()

    @Suppress("DEPRECATION") // allNetworks: still the simplest way to enumerate on API 24-35
    fun candidates(ctx: Context): List<Local> {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val out = mutableListOf<Pair<Int, Local>>()
        for (net in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val transport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                else -> continue
            }
            val lp = cm.getLinkProperties(net) ?: continue
            for (la in lp.linkAddresses) {
                val a = la.address as? Inet4Address ?: continue
                if (a.isLoopbackAddress || a.isLinkLocalAddress) continue
                val ip = a.hostAddress ?: continue
                val score = (if (Ipv4.isPrivate(ip)) 0 else 10) + (if (net == cm.activeNetwork) 0 else 1)
                out += score to Local(ip, la.prefixLength, transport, lp.interfaceName)
            }
        }
        return out.sortedBy { it.first }.map { it.second }
    }
}
