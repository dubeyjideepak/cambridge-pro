package com.cambridge.streamer.stream

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Embedded Wireless RTMP Ingest Server (Port 1935)
 *
 * Listens on 0.0.0.0:1935 (ALL network interfaces simultaneously):
 * - Mobile Hotspot   (e.g. 192.168.43.1:1935)
 * - Shared Wi-Fi     (e.g. 192.168.1.x:1935)
 *
 * Sony Alpha 7 IV (or any RTMP encoder/camera) streams to:
 *   rtmp://<phone-ip>:1935/live
 *
 * Complete protocol stack:
 * 1. RTMP Handshake (C0/C1/C2 <-> S0/S1/S2)
 * 2. Window Ack Size, Set Peer Bandwidth, Set Chunk Size
 * 3. Full AMF0 command negotiation:
 *    - connect       -> _result (NetConnection.Connect.Success)
 *    - releaseStream -> _result
 *    - FCPublish     -> _result
 *    - createStream  -> _result (streamId=1)
 *    - publish       -> onStatus (NetStream.Publish.Start)
 * 4. H.264 Sequence Header parsing (SPS/PPS extraction) + NALU extraction (AVCC -> Annex B)
 * 5. AAC Sequence Header (ASC) + raw AAC frame extraction
 */
class RtmpIngestServer(
    val port: Int = 1935,
    private val onClientConnected: (String) -> Unit = {},
    private val onSpsPpsReady: (ByteArray, ByteArray) -> Unit = { _, _ -> },
    private val onVideoNalu: (ByteArray, Boolean, Long) -> Unit = { _, _, _ -> },
    private val onAudioConfig: (ByteArray) -> Unit = {},
    private val onAudioFrame: (ByteArray, Long) -> Unit = { _, _ -> },
    private val onClientDisconnected: (String) -> Unit = {}
) {
    private val TAG = "RtmpIngestServer"

    @Volatile private var isRunning = false
    private var serverSocket: ServerSocket? = null
    private var clientThread: Thread? = null
    private var acceptThread: Thread? = null

    @Volatile private var connectedClient: Socket? = null
    private val hasClient = AtomicBoolean(false)

    @Volatile var isClientConnected: Boolean = false
        private set

    @Volatile var clientIp: String? = null
        private set

    @Volatile var sps: ByteArray? = null
        private set
    @Volatile var pps: ByteArray? = null
        private set
    @Volatile var asc: ByteArray? = null
        private set

    // RTMP Chunk Stream IDs
    private val CS_CONTROL = 2
    private val CS_COMMAND = 3

    fun start() {
        if (isRunning) return
        isRunning = true

        acceptThread = Thread({
            try {
                // Bind to 0.0.0.0 — listens on ALL network interfaces (hotspot + shared Wi-Fi)
                serverSocket = ServerSocket(port).apply {
                    reuseAddress = true
                    soTimeout = 0
                }
                Log.d(TAG, "RTMP Ingest Server bound to 0.0.0.0:$port (Hotspot + Wi-Fi)")

                while (isRunning) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        val ip = client.inetAddress.hostAddress ?: "unknown"
                        Log.d(TAG, "Sony / RTMP camera connected from: $ip")

                        // Close previous camera connection if one was already active
                        connectedClient?.let {
                            try { it.close() } catch (_: Exception) {}
                        }

                        connectedClient = client
                        clientIp = ip
                        hasClient.set(true)
                        isClientConnected = true
                        onClientConnected(ip)

                        // Launch dedicated thread for this client
                        clientThread?.interrupt()
                        clientThread = Thread({
                            handleClient(client)
                        }, "rtmp-ingest-client").apply {
                            isDaemon = true
                            start()
                        }
                    } catch (e: SocketException) {
                        if (isRunning) Log.w(TAG, "Accept interrupted: ${e.message}")
                        break
                    }
                }
            } catch (e: Exception) {
                if (isRunning) Log.e(TAG, "RTMP server exception", e)
            } finally {
                Log.d(TAG, "RTMP Ingest Server accept loop ended")
            }
        }, "rtmp-ingest-accept")
        acceptThread!!.isDaemon = true
        acceptThread!!.start()
    }

    fun stop() {
        isRunning = false
        isClientConnected = false
        hasClient.set(false)
        clientIp = null

        try { connectedClient?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}

        acceptThread?.interrupt()
        clientThread?.interrupt()
        connectedClient = null
        Log.d(TAG, "RTMP Ingest Server stopped")
    }

    // ─── Client Connection Handler ─────────────────────────────────────────────

    private fun handleClient(socket: Socket) {
        var disconnectReason = "normal disconnect"
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 15_000 // 15-second inactivity timeout

            val input = DataInputStream(socket.getInputStream().buffered(128 * 1024))
            val output = socket.getOutputStream().buffered(64 * 1024)

            // Step 1: Handshake
            if (!performHandshake(input, output)) {
                disconnectReason = "handshake failed"
                Log.e(TAG, "RTMP handshake failed with ${socket.inetAddress.hostAddress}")
                return
            }
            Log.d(TAG, "RTMP handshake completed successfully with ${socket.inetAddress.hostAddress}")

            // Step 2: Protocol control & initial chunk size
            sendProtocolControl(output)

            // Step 3: Message read loop (AMF0 commands, H.264 video, AAC audio)
            val state = RtmpState()
            while (isRunning && !socket.isClosed) {
                val header = readChunkHeader(input, state) ?: break
                val payload = readPayload(input, header, state) ?: continue
                dispatchMessage(header, payload, output)
            }
        } catch (e: Exception) {
            if (isRunning) {
                disconnectReason = e.message ?: "io error"
                Log.w(TAG, "RTMP client session ended: $disconnectReason")
            }
        } finally {
            isClientConnected = false
            hasClient.set(false)
            clientIp = null
            try { socket.close() } catch (_: Exception) {}
            onClientDisconnected(disconnectReason)
            Log.d(TAG, "RTMP client disconnected: $disconnectReason")
        }
    }

    // ─── Handshake Implementation (C0/C1/C2 <-> S0/S1/S2) ─────────────────────

    private fun performHandshake(input: DataInputStream, output: OutputStream): Boolean {
        return try {
            val c0 = input.readByte().toInt() and 0xFF
            if (c0 != 3) {
                Log.e(TAG, "Invalid RTMP version byte: $c0")
                return false
            }

            val c1 = ByteArray(1536)
            input.readFully(c1)

            // S0: version (3)
            output.write(3)
            // S1: 1536 bytes
            val s1 = ByteArray(1536)
            val nowMs = System.currentTimeMillis().toInt()
            s1[0] = ((nowMs shr 24) and 0xFF).toByte()
            s1[1] = ((nowMs shr 16) and 0xFF).toByte()
            s1[2] = ((nowMs shr 8) and 0xFF).toByte()
            s1[3] = (nowMs and 0xFF).toByte()
            output.write(s1)
            // S2: echo C1 back to client
            output.write(c1)
            output.flush()

            // Read C2: 1536 bytes
            val c2 = ByteArray(1536)
            input.readFully(c2)

            true
        } catch (e: Exception) {
            Log.e(TAG, "Handshake error: ${e.message}")
            false
        }
    }

    private fun sendProtocolControl(output: OutputStream) {
        // Window Ack Size: 2,500,000 bytes
        sendControlMessage(output, 0x05, 2500000)
        // Set Peer Bandwidth: 2,500,000 bytes, limit type 2 (Dynamic)
        sendSetPeerBandwidth(output, 2500000, 2)
        // Set Chunk Size: 65,536 bytes
        sendControlMessage(output, 0x01, 65536)
        output.flush()
    }

    private fun sendControlMessage(output: OutputStream, typeId: Int, value: Int) {
        val payload = byteArrayOf(
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
        sendRtmpMessage(output, CS_CONTROL, typeId, 0, 0L, payload)
    }

    private fun sendSetPeerBandwidth(output: OutputStream, value: Int, limitType: Int) {
        val payload = byteArrayOf(
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
            (limitType and 0xFF).toByte()
        )
        sendRtmpMessage(output, CS_CONTROL, 0x06, 0, 0L, payload)
    }

    // ─── Chunk Reading ─────────────────────────────────────────────────────────

    data class ChunkHeader(
        val csId: Int,
        val timestamp: Long,
        val messageLength: Int,
        val messageTypeId: Int,
        val streamId: Int
    )

    inner class RtmpState {
        val lastHeaders = HashMap<Int, ChunkHeader>()
        val partialPayloads = HashMap<Int, ByteBuffer>()
        var inChunkSize = 128
    }

    private fun readChunkHeader(input: DataInputStream, state: RtmpState): ChunkHeader? {
        return try {
            val basicHeaderByte = input.readUnsignedByte()
            val fmt = (basicHeaderByte ushr 6) and 0x03
            val csIdRaw = basicHeaderByte and 0x3F

            val csId = when (csIdRaw) {
                0 -> input.readUnsignedByte() + 64
                1 -> input.readUnsignedByte() + input.readUnsignedByte() * 256 + 64
                else -> csIdRaw
            }

            val prev = state.lastHeaders[csId]

            val (timestamp, messageLength, messageTypeId, streamId) = when (fmt) {
                0 -> { // Full 11-byte header
                    val ts = readUInt24(input)
                    val len = readUInt24(input)
                    val typeId = input.readUnsignedByte()
                    val sid = readLittleEndianInt(input)
                    val finalTs = if (ts == 0xFFFFFF) readUInt32(input) else ts.toLong()
                    Quad(finalTs, len, typeId, sid)
                }
                1 -> { // 7-byte header (timestamp delta + len + type)
                    val tsDelta = readUInt24(input)
                    val len = readUInt24(input)
                    val typeId = input.readUnsignedByte()
                    val sid = prev?.streamId ?: 0
                    val prevTs = prev?.timestamp ?: 0L
                    val finalDelta = if (tsDelta == 0xFFFFFF) readUInt32(input) else tsDelta.toLong()
                    Quad(prevTs + finalDelta, len, typeId, sid)
                }
                2 -> { // 3-byte header (timestamp delta only)
                    val tsDelta = readUInt24(input)
                    val len = prev?.messageLength ?: 0
                    val typeId = prev?.messageTypeId ?: 0
                    val sid = prev?.streamId ?: 0
                    val prevTs = prev?.timestamp ?: 0L
                    val finalDelta = if (tsDelta == 0xFFFFFF) readUInt32(input) else tsDelta.toLong()
                    Quad(prevTs + finalDelta, len, typeId, sid)
                }
                else -> { // fmt 3: reuse header
                    if (prev == null) return null
                    Quad(prev.timestamp, prev.messageLength, prev.messageTypeId, prev.streamId)
                }
            }

            ChunkHeader(csId, timestamp, messageLength, messageTypeId, streamId).also {
                state.lastHeaders[csId] = it
            }
        } catch (e: Exception) {
            null
        }
    }

    private data class Quad(val a: Long, val b: Int, val c: Int, val d: Int)

    private fun readPayload(input: DataInputStream, header: ChunkHeader, state: RtmpState): ByteArray? {
        return try {
            val csId = header.csId
            val buf = state.partialPayloads.getOrPut(csId) {
                ByteBuffer.allocate(header.messageLength)
            }

            val remaining = buf.remaining()
            val toRead = minOf(remaining, state.inChunkSize)

            val chunk = ByteArray(toRead)
            input.readFully(chunk)
            buf.put(chunk)

            if (!buf.hasRemaining()) {
                state.partialPayloads.remove(csId)
                buf.array().copyOf(header.messageLength)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ─── RTMP Message Dispatch ─────────────────────────────────────────────────

    private fun dispatchMessage(header: ChunkHeader, payload: ByteArray, output: OutputStream) {
        when (header.messageTypeId) {
            1 -> { // Set Chunk Size from client
                if (payload.size >= 4) {
                    val newSize = ((payload[0].toInt() and 0x7F) shl 24) or
                            ((payload[1].toInt() and 0xFF) shl 16) or
                            ((payload[2].toInt() and 0xFF) shl 8) or
                            (payload[3].toInt() and 0xFF)
                    Log.d(TAG, "Client requested chunk size: $newSize")
                }
            }
            0x14, 0x11 -> { // AMF0 / AMF3 Command Message
                handleAmf0Command(payload, output)
            }
            9 -> { // H.264 Video Tag
                parseVideoMessage(payload, header.timestamp)
            }
            8 -> { // AAC Audio Tag
                parseAudioMessage(payload, header.timestamp)
            }
            else -> {
                // Ignore metadata or window acks
            }
        }
    }

    // ─── AMF0 Command Handling ─────────────────────────────────────────────────

    private fun handleAmf0Command(payload: ByteArray, output: OutputStream) {
        try {
            val cmd = readAmfString(payload, 0)
            val txId = readAmfNumber(payload, getNextOffsetAfterString(payload, 0))
            Log.d(TAG, "Received AMF0 command: '$cmd' txId=$txId")

            when (cmd) {
                "connect" -> {
                    val resp = AmfEncoder.buildConnectSuccessResponse(txId)
                    sendRtmpMessage(output, CS_COMMAND, 0x14, 0, 0L, resp)
                    output.flush()
                    Log.d(TAG, "Responded to connect command")
                }
                "releaseStream", "FCPublish" -> {
                    val resp = AmfEncoder.buildGenericSuccessResponse(txId)
                    sendRtmpMessage(output, CS_COMMAND, 0x14, 0, 0L, resp)
                    output.flush()
                    Log.d(TAG, "Responded to $cmd command")
                }
                "createStream" -> {
                    val resp = AmfEncoder.buildCreateStreamSuccessResponse(txId, 1.0)
                    sendRtmpMessage(output, CS_COMMAND, 0x14, 0, 0L, resp)
                    output.flush()
                    Log.d(TAG, "Responded to createStream command (streamId=1)")
                }
                "publish" -> {
                    val resp = AmfEncoder.buildPublishStartResponse()
                    sendRtmpMessage(output, CS_COMMAND, 0x14, 1, 0L, resp)
                    output.flush()
                    Log.d(TAG, "Responded to publish command -> Sony camera now streaming live!")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling AMF command: ${e.message}", e)
        }
    }

    private fun sendRtmpMessage(
        output: OutputStream,
        csId: Int,
        msgType: Int,
        msgStreamId: Int,
        timestamp: Long,
        payload: ByteArray,
        outChunkSize: Int = 65536
    ) {
        var offset = 0
        var isFirstChunk = true

        while (offset < payload.size) {
            val chunkSize = minOf(payload.size - offset, outChunkSize)

            if (isFirstChunk) {
                val header = ByteArray(12)
                header[0] = ((0 shl 6) or (csId and 0x3F)).toByte()
                val ts = if (timestamp >= 0xFFFFFF) 0xFFFFFF else timestamp.toInt()
                header[1] = ((ts shr 16) and 0xFF).toByte()
                header[2] = ((ts shr 8) and 0xFF).toByte()
                header[3] = (ts and 0xFF).toByte()
                header[4] = ((payload.size shr 16) and 0xFF).toByte()
                header[5] = ((payload.size shr 8) and 0xFF).toByte()
                header[6] = (payload.size and 0xFF).toByte()
                header[7] = (msgType and 0xFF).toByte()
                header[8] = (msgStreamId and 0xFF).toByte()
                header[9] = ((msgStreamId shr 8) and 0xFF).toByte()
                header[10] = ((msgStreamId shr 16) and 0xFF).toByte()
                header[11] = ((msgStreamId shr 24) and 0xFF).toByte()

                output.write(header)
                isFirstChunk = false
            } else {
                output.write(((3 shl 6) or (csId and 0x3F)))
            }

            output.write(payload, offset, chunkSize)
            offset += chunkSize
        }
    }

    // ─── H.264 Video Parsing (AVCC -> Annex B) ─────────────────────────────────

    private fun parseVideoMessage(data: ByteArray, ptsMs: Long) {
        if (data.size < 5) return
        val frameType = (data[0].toInt() and 0xFF) ushr 4
        val codecId = (data[0].toInt() and 0xFF) and 0x0F
        val avcPacketType = data[1].toInt() and 0xFF

        if (codecId != 7) return // Only H.264 / AVC

        if (avcPacketType == 0) {
            // AVC Sequence Header -> extract SPS and PPS
            extractSpsPpsFromAvcc(data, 5)
            return
        }

        if (avcPacketType == 1 && data.size > 9) {
            val isKeyFrame = (frameType == 1)
            val naluData = extractNaluFromFlv(data, 5)
            if (naluData.isNotEmpty()) {
                onVideoNalu(naluData, isKeyFrame, ptsMs)
            }
        }
    }

    private fun extractSpsPpsFromAvcc(data: ByteArray, offset: Int) {
        try {
            if (data.size < offset + 8) return
            val numSps = data[offset + 5].toInt() and 0x1F
            var pos = offset + 6
            for (i in 0 until numSps) {
                if (pos + 2 > data.size) return
                val spsLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                pos += 2
                if (pos + spsLen > data.size) return
                val rawSps = data.copyOfRange(pos, pos + spsLen)
                // Prefix with Annex B start code 0x00 0x00 0x00 0x01
                sps = byteArrayOf(0x00, 0x00, 0x00, 0x01) + rawSps
                pos += spsLen
            }

            if (pos >= data.size) return
            val numPps = data[pos].toInt() and 0xFF
            pos++
            for (i in 0 until numPps) {
                if (pos + 2 > data.size) return
                val ppsLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                pos += 2
                if (pos + ppsLen > data.size) return
                val rawPps = data.copyOfRange(pos, pos + ppsLen)
                pps = byteArrayOf(0x00, 0x00, 0x00, 0x01) + rawPps
                pos += ppsLen
            }

            if (sps != null && pps != null) {
                Log.d(TAG, "Extracted SPS (${sps!!.size}B) and PPS (${pps!!.size}B) from Sony camera")
                onSpsPpsReady(sps!!, pps!!)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting SPS/PPS: ${e.message}")
        }
    }

    private fun extractNaluFromFlv(data: ByteArray, offset: Int): ByteArray {
        return try {
            val out = ByteArrayOutputStream()
            var pos = offset
            while (pos + 4 <= data.size) {
                val naluLen = ((data[pos].toInt() and 0xFF) shl 24) or
                        ((data[pos + 1].toInt() and 0xFF) shl 16) or
                        ((data[pos + 2].toInt() and 0xFF) shl 8) or
                        (data[pos + 3].toInt() and 0xFF)
                pos += 4
                if (naluLen <= 0 || pos + naluLen > data.size) break

                // Write Annex B start code prefix
                out.write(byteArrayOf(0x00, 0x00, 0x00, 0x01))
                out.write(data, pos, naluLen)
                pos += naluLen
            }
            out.toByteArray()
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    // ─── AAC Audio Parsing ─────────────────────────────────────────────────────

    private fun parseAudioMessage(data: ByteArray, ptsMs: Long) {
        if (data.size < 2) return
        val soundFormat = (data[0].toInt() and 0xFF) ushr 4
        if (soundFormat != 10) return // Only AAC

        val aacPacketType = data[1].toInt() and 0xFF
        if (aacPacketType == 0 && data.size > 2) {
            // AudioSpecificConfig
            val config = data.copyOfRange(2, data.size)
            asc = config
            Log.d(TAG, "Extracted AudioSpecificConfig (${config.size}B) from Sony camera")
            onAudioConfig(config)
        } else if (aacPacketType == 1 && data.size > 2) {
            val rawAac = data.copyOfRange(2, data.size)
            onAudioFrame(rawAac, ptsMs)
        }
    }

    // ─── AMF0 Parsing Helpers ──────────────────────────────────────────────────

    private fun readAmfString(payload: ByteArray, offset: Int): String {
        if (offset >= payload.size || payload[offset] != 0x02.toByte()) return ""
        val len = ((payload[offset + 1].toInt() and 0xFF) shl 8) or (payload[offset + 2].toInt() and 0xFF)
        return if (offset + 3 + len <= payload.size) {
            String(payload, offset + 3, len, Charsets.UTF_8)
        } else ""
    }

    private fun getNextOffsetAfterString(payload: ByteArray, offset: Int): Int {
        if (offset >= payload.size || payload[offset] != 0x02.toByte()) return offset
        val len = ((payload[offset + 1].toInt() and 0xFF) shl 8) or (payload[offset + 2].toInt() and 0xFF)
        return offset + 3 + len
    }

    private fun readAmfNumber(payload: ByteArray, offset: Int): Double {
        if (offset >= payload.size || payload[offset] != 0x00.toByte()) return 0.0
        if (offset + 9 > payload.size) return 0.0
        var bits = 0L
        for (i in 0 until 8) {
            bits = (bits shl 8) or (payload[offset + 1 + i].toLong() and 0xFF)
        }
        return java.lang.Double.longBitsToDouble(bits)
    }

    private fun readUInt24(input: DataInputStream): Int {
        return (input.readUnsignedByte() shl 16) or
                (input.readUnsignedByte() shl 8) or
                input.readUnsignedByte()
    }

    private fun readUInt32(input: DataInputStream): Long {
        return ((input.readUnsignedByte().toLong() shl 24) or
                (input.readUnsignedByte().toLong() shl 16) or
                (input.readUnsignedByte().toLong() shl 8) or
                input.readUnsignedByte().toLong())
    }

    private fun readLittleEndianInt(input: DataInputStream): Int {
        return input.readUnsignedByte() or
                (input.readUnsignedByte() shl 8) or
                (input.readUnsignedByte() shl 16) or
                (input.readUnsignedByte() shl 24)
    }
}
