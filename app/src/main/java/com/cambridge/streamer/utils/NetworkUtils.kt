package com.cambridge.streamer.utils

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    data class NetworkEndpoints(
        val wifiRouterIp: String?,
        val hotspotIp: String,
        val bestIp: String
    )

    /**
     * Inspects all active network interfaces to detect:
     * 1. Local Wi-Fi Router IP (e.g., wlan0 connected to home router)
     * 2. Mobile Hotspot IP (e.g., ap0 / softap0 / rndis0 / 192.168.43.1)
     */
    fun getNetworkEndpoints(): NetworkEndpoints {
        var wifiIp: String? = null
        var hotspotIp: String? = null

        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()

                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("127.")) continue

                        // Check if interface is hotspot/AP/tethering
                        if (name.startsWith("ap") ||
                            name.startsWith("softap") ||
                            name.startsWith("swlan") ||
                            name.startsWith("rndis") ||
                            name.contains("tether") ||
                            host.startsWith("192.168.43.") ||
                            host.startsWith("192.168.49.") ||
                            host.startsWith("192.168.50.")
                        ) {
                            hotspotIp = host
                        } else if (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("en")) {
                            wifiIp = host
                        } else {
                            if (wifiIp == null) wifiIp = host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Standard Android default hotspot gateway IP if not specifically bound to separate interface name
        val resolvedHotspot = hotspotIp ?: "192.168.43.1"
        val best = wifiIp ?: resolvedHotspot

        return NetworkEndpoints(
            wifiRouterIp = wifiIp,
            hotspotIp = resolvedHotspot,
            bestIp = best
        )
    }

    /**
     * Finds the local IPv4 address of the device on the Wi-Fi network.
     * e.g., "192.168.1.105"
     */
    fun getLocalIpAddress(): String {
        return getNetworkEndpoints().bestIp
    }
}
