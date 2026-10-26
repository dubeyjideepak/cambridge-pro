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
     * 2. Mobile Hotspot IP (e.g., ap0 / softap0 / rndis0 / 10.x.x.x / 192.168.43.1)
     */
    fun getNetworkEndpoints(): NetworkEndpoints {
        var wifiIp: String? = null
        var hotspotIp: String? = null
        val allIps = mutableListOf<String>()

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
                        allIps.add(host)

                        // Check if interface is hotspot/AP/tethering
                        if (name.startsWith("ap") ||
                            name.startsWith("softap") ||
                            name.startsWith("swlan") ||
                            name.startsWith("rndis") ||
                            name.contains("tether") ||
                            name.contains("hotspot") ||
                            host.startsWith("192.168.43.") ||
                            host.startsWith("192.168.49.") ||
                            host.startsWith("192.168.50.") ||
                            (host.startsWith("10.") && !name.startsWith("rmnet"))
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

        val resolvedHotspot = hotspotIp ?: allIps.firstOrNull { it != wifiIp } ?: "192.168.43.1"
        val best = wifiIp ?: resolvedHotspot

        return NetworkEndpoints(
            wifiRouterIp = wifiIp,
            hotspotIp = resolvedHotspot,
            bestIp = best
        )
    }

    /**
     * Returns all active non-loopback IPv4 addresses on the phone.
     */
    fun getAllLocalIpv4Addresses(): List<String> {
        val list = mutableListOf<String>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (!host.startsWith("127.")) {
                            list.add(host)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list.distinct()
    }

    /**
     * Finds the primary local IPv4 address of the device.
     */
    fun getLocalIpAddress(): String {
        return getNetworkEndpoints().bestIp
    }
}
