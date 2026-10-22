package com.cambridge.streamer.stream

import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Production Dual-Track RTSP Server for OBS Studio (RFC 2326, RFC 6184 H.264 & RFC 3640 AAC)
 *
 * Multiplexes and streams both:
 * - Track 0: 4K/1080p H.264 Camera Video
 * - Track 1: 44.1 kHz AAC Camera HDMI Audio
 *
 * Supports:
 * - TCP Interleaved Mode (RTP/AVP/TCP) - Rock-solid for OBS, zero firewall issues
 * - UDP Mode (RTP/AVP/UDP)
 */
class RtspServerManager(
    private val port: Int = 8554,
    private val getSpsPps: () -> Pair<ByteArray?, ByteArray?>,
    private val getAudioConfig: () -> ByteArray? = { null },
    private val onRequestKeyFrame: (() -> Unit)? = null
) {
    private val TAG = "RtspServer"
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false
    private val clientSessions = ConcurrentHashMap<String, RtspClientSession>()
    private val sessionCounter = AtomicInteger(1000)

    fun start() {
        if (isRunning) return
        isRunning = true

        Thread {
            try {
                serverSocket = ServerSocket(port).apply {
                    reuseAddress = true
                }
                Log.d(TAG, "RTSP Server started on port $port (Video + Audio dual-track)")

                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    val sessionId = sessionCounter.incrementAndGet().toString()
                    val session = RtspClientSession(socket, sessionId, getSpsPps, getAudioConfig, onRequestKeyFrame)
                    clientSessions[sessionId] = session
                    Thread(session, "RtspClient-$sessionId").start()
                }
            } catch (e: Exception) {
                if (isRunning) Log.e(TAG, "RTSP Server Error", e)
            }
        }.start()
    }

    fun broadcastH264Packet(frame: ByteArray, isKeyFrame: Boolean, ptsMs: Long) {
        if (clientSessions.isEmpty()) return

        val nalus = extractNalUnits(frame)
        if (nalus.isEmpty()) return

        for ((id, session) in clientSessions) {
            if (session.isPlaying) {
                for (i in nalus.indices) {
                    val nalu = nalus[i]
                    val isLastNalu = (i == nalus.size - 1)
                    session.sendVideoNalu(nalu, ptsMs, isLastNalu)
                }
            } else if (session.isClosed) {
                clientSessions.remove(id)
            }
        }
    }

    fun broadcastAudioPacket(rawAac: ByteArray, dtsMs: Long) {
        if (clientSessions.isEmpty()) return

        for ((id, session) in clientSessions) {
            if (session.isPlaying) {
                session.sendAudioFrame(rawAac, dtsMs)
            } else if (session.isClosed) {
                clientSessions.remove(id)
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
            clientSessions.values.forEach { it.close() }
            clientSessions.clear()
            Log.d(TAG, "RTSP Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping RTSP Server", e)
        }
    }

    private fun extractNalUnits(data: ByteArray): List<ByteArray> {
        val nalList = mutableListOf<ByteArray>()
        var startPos = -1
        var i = 0
        val len = data.size

        while (i < len - 3) {
            val is4ByteStart = (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte())
            val is3ByteStart = (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 1.toByte())

            if (is4ByteStart || is3ByteStart) {
                val prefixLen = if (is4ByteStart) 4 else 3
                if (startPos != -1) {
                    val nalLen = i - startPos
                    if (nalLen > 0) {
                        val nal = ByteArray(nalLen)
                        System.arraycopy(data, startPos, nal, 0, nalLen)
                        nalList.add(nal)
                    }
                }
                startPos = i + prefixLen
                i += prefixLen
            } else {
                i++
            }
        }

        if (startPos != -1 && startPos < len) {
            val nalLen = len - startPos
            if (nalLen > 0) {
                val nal = ByteArray(nalLen)
                System.arraycopy(data, startPos, nal, 0, nalLen)
                nalList.add(nal)
            }
        } else if (startPos == -1 && len > 0) {
            nalList.add(data)
        }

        return nalList
    }

    class RtspClientSession(
        private val socket: Socket,
        val sessionId: String,
        private val getSpsPps: () -> Pair<ByteArray?, ByteArray?>,
        private val getAudioConfig: () -> ByteArray?,
        private val onRequestKeyFrame: (() -> Unit)? = null
    ) : Runnable {
        private val TAG = "RtspSession"
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        private val writer: OutputStream = socket.getOutputStream()

        var isPlaying = false
            private set
        var isClosed = false
            private set

        private var clientIp: InetAddress? = null

        // Video track (track0)
        private var isVideoTcp = true
        private var videoInterleavedChannel = 0
        private var videoRtpPort = 5004
        private var videoRtpSocket: DatagramSocket? = null
        private var videoSeq = 0
        private var videoTimestamp: Long = 0L

        // Audio track (track1)
        private var isAudioTcp = true
        private var audioInterleavedChannel = 2
        private var audioRtpPort = 5006
        private var audioRtpSocket: DatagramSocket? = null
        private var audioSeq = 0
        private var audioTimestamp: Long = 0L

        override fun run() {
            clientIp = socket.inetAddress
            try {
                while (!isClosed) {
                    val requestLine = reader.readLine() ?: break
                    if (requestLine.isEmpty()) continue

                    val parts = requestLine.split(" ")
                    if (parts.size < 3) continue
                    val method = parts[0]
                    val uri = parts[1]

                    var cSeq = 1
                    var transport = ""

                    var line: String
                    while (reader.readLine().also { line = it ?: "" }.isNotEmpty()) {
                        if (line.startsWith("CSeq:", ignoreCase = true)) {
                            cSeq = line.substringAfter(":").trim().toIntOrNull() ?: 1
                        } else if (line.startsWith("Transport:", ignoreCase = true)) {
                            transport = line.substringAfter(":").trim()
                        }
                    }

                    handleMethod(method, uri, cSeq, transport)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Session $sessionId ended: ${e.message}")
            } finally {
                close()
            }
        }

        private fun handleMethod(method: String, uri: String, cSeq: Int, transportHeader: String) {
            when (method) {
                "OPTIONS" -> {
                    val resp = "RTSP/1.0 200 OK\r\n" +
                            "CSeq: $cSeq\r\n" +
                            "Public: DESCRIBE, SETUP, TEARDOWN, PLAY, PAUSE\r\n\r\n"
                    synchronized(writer) {
                        writer.write(resp.toByteArray())
                        writer.flush()
                    }
                }
                "DESCRIBE" -> {
                    val (spsRaw, ppsRaw) = getSpsPps()
                    val sps = spsRaw?.let { stripStartCode(it) }
                    val pps = ppsRaw?.let { stripStartCode(it) }

                    val spsBase64 = sps?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: "Z00AKp2oHgCJ+WbgICAoAAADAAgAAAMBlCA="
                    val ppsBase64 = pps?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: "aO48gA=="

                    val audioConfig = getAudioConfig()
                    val audioConfigHex = if (audioConfig != null && audioConfig.isNotEmpty()) {
                        audioConfig.joinToString("") { "%02x".format(it) }
                    } else {
                        "1188" // Standard 48000Hz Mono AAC-LC (sampleRateIndex=3, channelConfig=1)
                    }

                    val sdp = "v=0\r\n" +
                            "o=- $sessionId 1 IN IP4 0.0.0.0\r\n" +
                            "s=CamBridge Live 4K Repeater Stream\r\n" +
                            "t=0 0\r\n" +
                            "m=video 0 RTP/AVP 96\r\n" +
                            "c=IN IP4 0.0.0.0\r\n" +
                            "a=rtpmap:96 H264/90000\r\n" +
                            "a=fmtp:96 packetization-mode=1;sprop-parameter-sets=$spsBase64,$ppsBase64\r\n" +
                            "a=control:track0\r\n" +
                            "m=audio 0 RTP/AVP 97\r\n" +
                            "c=IN IP4 0.0.0.0\r\n" +
                            "a=rtpmap:97 MPEG4-GENERIC/48000/1\r\n" +
                            "a=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr;config=$audioConfigHex;sizelength=13;indexlength=3;indexdeltalength=3\r\n" +
                            "a=control:track1\r\n"

                    val resp = "RTSP/1.0 200 OK\r\n" +
                            "CSeq: $cSeq\r\n" +
                            "Content-Type: application/sdp\r\n" +
                            "Content-Length: ${sdp.length}\r\n\r\n$sdp"
                    synchronized(writer) {
                        writer.write(resp.toByteArray())
                        writer.flush()
                    }
                }
                "SETUP" -> {
                    val isAudio = uri.contains("track1", ignoreCase = true)
                    val isTcp = transportHeader.contains("RTP/AVP/TCP", ignoreCase = true) ||
                            transportHeader.contains("interleaved", ignoreCase = true)

                    if (isTcp) {
                        val interMatch = Regex("interleaved=(\\d+)-?(\\d+)?").find(transportHeader)
                        val defaultChannel = if (isAudio) 2 else 0
                        val channel = interMatch?.groupValues?.get(1)?.toIntOrNull() ?: defaultChannel

                        if (isAudio) {
                            isAudioTcp = true
                            audioInterleavedChannel = channel
                        } else {
                            isVideoTcp = true
                            videoInterleavedChannel = channel
                        }

                        val resp = "RTSP/1.0 200 OK\r\n" +
                                "CSeq: $cSeq\r\n" +
                                "Session: $sessionId;timeout=60\r\n" +
                                "Transport: RTP/AVP/TCP;unicast;interleaved=$channel-${channel + 1}\r\n\r\n"
                        synchronized(writer) {
                            writer.write(resp.toByteArray())
                            writer.flush()
                        }
                    } else {
                        // UDP
                        val portMatch = Regex("client_port=(\\d+)-?(\\d+)?").find(transportHeader)
                        val defaultPort = if (isAudio) 5006 else 5004
                        val port = portMatch?.groupValues?.get(1)?.toIntOrNull() ?: defaultPort

                        if (isAudio) {
                            isAudioTcp = false
                            audioRtpPort = port
                            try { audioRtpSocket = DatagramSocket() } catch (_: Exception) {}
                        } else {
                            isVideoTcp = false
                            videoRtpPort = port
                            try { videoRtpSocket = DatagramSocket() } catch (_: Exception) {}
                        }

                        val serverPort = if (isAudio) "55502-55503" else "55500-55501"
                        val resp = "RTSP/1.0 200 OK\r\n" +
                                "CSeq: $cSeq\r\n" +
                                "Session: $sessionId;timeout=60\r\n" +
                                "Transport: RTP/AVP/UDP;unicast;client_port=$port-${port + 1};server_port=$serverPort\r\n\r\n"
                        synchronized(writer) {
                            writer.write(resp.toByteArray())
                            writer.flush()
                        }
                    }
                }
                "PLAY" -> {
                    isPlaying = true
                    onRequestKeyFrame?.invoke()
                    val resp = "RTSP/1.0 200 OK\r\n" +
                            "CSeq: $cSeq\r\n" +
                            "Session: $sessionId\r\n" +
                            "RTP-Info: url=track0;seq=$videoSeq,url=track1;seq=$audioSeq\r\n\r\n"
                    synchronized(writer) {
                        writer.write(resp.toByteArray())
                        writer.flush()
                    }
                }
                "TEARDOWN" -> {
                    isPlaying = false
                    val resp = "RTSP/1.0 200 OK\r\n" +
                            "CSeq: $cSeq\r\n\r\n"
                    synchronized(writer) {
                        writer.write(resp.toByteArray())
                        writer.flush()
                    }
                    close()
                }
            }
        }

        // Shared epoch reference for video/audio synchronization (zero drift)
        @Volatile private var baseTimeMs: Long = -1L

        fun sendVideoNalu(nalu: ByteArray, ptsMs: Long, isLastNaluInAccessUnit: Boolean) {
            if (nalu.isEmpty()) return
            val ip = clientIp ?: return

            val nalHeader = nalu[0]
            val nalType = (nalHeader.toInt() and 0x1F)
            val nri = (nalHeader.toInt() and 0x60)

            if (nalType == 0 || nalType > 23) return

            // Hardware presentation timestamp synchronization (90,000 Hz clock = 90 ticks/ms)
            if (baseTimeMs == -1L && ptsMs > 0) {
                baseTimeMs = ptsMs
            }
            val elapsedMs = if (baseTimeMs != -1L) maxOf(0L, ptsMs - baseTimeMs) else 0L
            val videoTimestamp = (elapsedMs * 90L) and 0xFFFFFFFFL

            if (nalu.size <= 1400) {
                val rtpPacket = ByteArray(12 + nalu.size)
                rtpPacket[0] = 0x80.toByte()
                // RFC 6184: Marker bit = 1 only on the final NAL unit of the access unit
                val marker = if (isLastNaluInAccessUnit) 0xE0 else 0x60
                rtpPacket[1] = marker.toByte() // PT = 96
                rtpPacket[2] = ((videoSeq shr 8) and 0xFF).toByte()
                rtpPacket[3] = (videoSeq and 0xFF).toByte()
                rtpPacket[4] = ((videoTimestamp shr 24) and 0xFF).toByte()
                rtpPacket[5] = ((videoTimestamp shr 16) and 0xFF).toByte()
                rtpPacket[6] = ((videoTimestamp shr 8) and 0xFF).toByte()
                rtpPacket[7] = (videoTimestamp and 0xFF).toByte()
                rtpPacket[8] = 0x12; rtpPacket[9] = 0x34; rtpPacket[10] = 0x56; rtpPacket[11] = 0x78

                System.arraycopy(nalu, 0, rtpPacket, 12, nalu.size)
                videoSeq = (videoSeq + 1) and 0xFFFF

                dispatchRtpPacket(rtpPacket, isVideoTcp, videoInterleavedChannel, videoRtpSocket, videoRtpPort, ip)
            } else {
                var offset = 1

                while (offset < nalu.size) {
                    val remaining = nalu.size - offset
                    val payloadSize = minOf(remaining, 1380)
                    val isStart = (offset == 1)
                    val isEnd = (offset + payloadSize >= nalu.size)

                    val rtpPacket = ByteArray(12 + 2 + payloadSize)
                    rtpPacket[0] = 0x80.toByte()
                    // RFC 6184: Marker bit = 1 only on the very last FU-A packet of the access unit
                    val marker = if (isEnd && isLastNaluInAccessUnit) 0xE0 else 0x60
                    rtpPacket[1] = marker.toByte()
                    rtpPacket[2] = ((videoSeq shr 8) and 0xFF).toByte()
                    rtpPacket[3] = (videoSeq and 0xFF).toByte()
                    rtpPacket[4] = ((videoTimestamp shr 24) and 0xFF).toByte()
                    rtpPacket[5] = ((videoTimestamp shr 16) and 0xFF).toByte()
                    rtpPacket[6] = ((videoTimestamp shr 8) and 0xFF).toByte()
                    rtpPacket[7] = (videoTimestamp and 0xFF).toByte()
                    rtpPacket[8] = 0x12; rtpPacket[9] = 0x34; rtpPacket[10] = 0x56; rtpPacket[11] = 0x78

                    rtpPacket[12] = (nri or 28).toByte()

                    var fuHeader = nalType
                    if (isStart) fuHeader = fuHeader or 0x80
                    if (isEnd) fuHeader = fuHeader or 0x40
                    rtpPacket[13] = fuHeader.toByte()

                    System.arraycopy(nalu, offset, rtpPacket, 14, payloadSize)
                    offset += payloadSize
                    videoSeq = (videoSeq + 1) and 0xFFFF

                    dispatchRtpPacket(rtpPacket, isVideoTcp, videoInterleavedChannel, videoRtpSocket, videoRtpPort, ip)
                }
            }
        }

        fun sendAudioFrame(rawAac: ByteArray, dtsMs: Long) {
            if (rawAac.isEmpty()) return
            val ip = clientIp ?: return

            // Audio presentation timestamp synchronization (48,000 Hz clock = 48 ticks/ms)
            if (baseTimeMs == -1L && dtsMs > 0) {
                baseTimeMs = dtsMs
            }
            val elapsedMs = if (baseTimeMs != -1L) maxOf(0L, dtsMs - baseTimeMs) else 0L
            val audioTimestamp = (elapsedMs * 48L) and 0xFFFFFFFFL

            val rtpPacket = ByteArray(12 + 4 + rawAac.size)
            rtpPacket[0] = 0x80.toByte()
            rtpPacket[1] = 0xE1.toByte() // Marker bit = 1, PT = 97
            rtpPacket[2] = ((audioSeq shr 8) and 0xFF).toByte()
            rtpPacket[3] = (audioSeq and 0xFF).toByte()
            rtpPacket[4] = ((audioTimestamp shr 24) and 0xFF).toByte()
            rtpPacket[5] = ((audioTimestamp shr 16) and 0xFF).toByte()
            rtpPacket[6] = ((audioTimestamp shr 8) and 0xFF).toByte()
            rtpPacket[7] = (audioTimestamp and 0xFF).toByte()
            rtpPacket[8] = 0x12; rtpPacket[9] = 0x34; rtpPacket[10] = 0x56; rtpPacket[11] = 0x79

            // AU-headers-length (16 bits)
            rtpPacket[12] = 0x00
            rtpPacket[13] = 0x10

            // AU-header: AU-size (13 bits) | AU-index (3 bits = 0)
            val auSize = rawAac.size
            rtpPacket[14] = ((auSize and 0x1FE0) shr 5).toByte()
            rtpPacket[15] = ((auSize and 0x1F) shl 3).toByte()

            System.arraycopy(rawAac, 0, rtpPacket, 16, rawAac.size)
            audioSeq = (audioSeq + 1) and 0xFFFF

            dispatchRtpPacket(rtpPacket, isAudioTcp, audioInterleavedChannel, audioRtpSocket, audioRtpPort, ip)
        }

        private fun dispatchRtpPacket(
            packet: ByteArray,
            isTcp: Boolean,
            interleavedChan: Int,
            udpSocket: DatagramSocket?,
            udpPort: Int,
            ip: InetAddress
        ) {
            try {
                if (isTcp) {
                    val channelHeader = byteArrayOf(
                        0x24.toByte(), // '$'
                        interleavedChan.toByte(),
                        ((packet.size shr 8) and 0xFF).toByte(),
                        (packet.size and 0xFF).toByte()
                    )
                    synchronized(writer) {
                        writer.write(channelHeader)
                        writer.write(packet)
                        writer.flush()
                    }
                } else {
                    val sock = udpSocket ?: return
                    val udpPacket = DatagramPacket(packet, packet.size, ip, udpPort)
                    sock.send(udpPacket)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Error sending RTP packet to session $sessionId: ${e.message}")
                close()
            }
        }

        private fun stripStartCode(data: ByteArray): ByteArray {
            return when {
                data.size > 4 && data[0] == 0x00.toByte() && data[1] == 0x00.toByte() &&
                        data[2] == 0x00.toByte() && data[3] == 0x01.toByte() -> data.copyOfRange(4, data.size)
                data.size > 3 && data[0] == 0x00.toByte() && data[1] == 0x00.toByte() &&
                        data[2] == 0x01.toByte() -> data.copyOfRange(3, data.size)
                else -> data
            }
        }

        fun close() {
            isClosed = true
            isPlaying = false
            try { videoRtpSocket?.close() } catch (_: Exception) {}
            try { audioRtpSocket?.close() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
