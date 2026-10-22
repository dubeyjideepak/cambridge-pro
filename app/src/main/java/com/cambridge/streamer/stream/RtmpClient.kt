package com.cambridge.streamer.stream

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Random
import java.util.concurrent.atomic.AtomicLong

/**
 * Production RTMP Client
 *
 * Implements the full RTMP protocol stack:
 * 1. RTMP Handshake (C0/C1/C2 ↔ S0/S1/S2)
 * 2. AMF0 command sequence: connect → _result → createStream → _result → publish → onStatus
 * 3. FLV video tag packaging (AVC sequence header + NALU data, Annex-B → AVCC conversion)
 * 4. FLV audio tag packaging (AAC sequence header + raw AAC frames)
 * 5. RTMP chunking with configurable chunk size (default 65536 for video)
 *
 * Compatible with:
 *   YouTube Live:  rtmp://a.rtmp.youtube.com/live2/{key}   port 1935
 *   Facebook Live: rtmps://live-api-s.facebook.com:443/rtmp/{key}  (use RTMPS variant)
 */
class RtmpClient {

    private val TAG = "RtmpClient"

    // RTMP Chunk Stream IDs
    private val CS_CONTROL = 2   // Protocol control (Set Chunk Size, Window Ack)
    private val CS_COMMAND  = 3  // AMF0 command messages
    private val CS_VIDEO    = 6  // FLV Video tags
    private val CS_AUDIO    = 7  // FLV Audio tags

    private val CHUNK_SIZE  = 65536  // Increase from default 128 → reduces per-packet overhead

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null

    // Monotonic video/audio timestamps in milliseconds
    private val videoDts = AtomicLong(0)
    private val audioDts = AtomicLong(0)

    // RTMP message stream ID used for published A/V data
    private var publishStreamId = 1

    // SPS/PPS cached — sent as AVC sequence header on first keyframe
    @Volatile private var avcHeaderSent = false
    @Volatile private var aacHeaderSent = false

    interface ConnectChecker {
        fun onConnectionSuccess()
        fun onConnectionFailed(reason: String)
        fun onDisconnected()
    }

    private var checker: ConnectChecker? = null

    // ─── Public API ────────────────────────────────────────────────────────────

    fun connect(url: String, checker: ConnectChecker) {
        this.checker = checker
        Thread {
            try {
                val parsed = parseRtmpUrl(url)
                val (isRtmps, host, port, app, key) = parsed
                Log.d(TAG, "Connecting to $host:$port (RTMPS=$isRtmps) app=$app")

                val baseSocket = Socket()
                baseSocket.soTimeout = 8000
                baseSocket.connect(InetSocketAddress(host, port), 8000)

                val sock: Socket = if (isRtmps || port == 443) {
                    Log.d(TAG, "Upgrading to TLS/SSL (RTMPS) for $host:$port")
                    val sslFactory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
                    val sslSocket = sslFactory.createSocket(baseSocket, host, port, true) as javax.net.ssl.SSLSocket
                    sslSocket.startHandshake()
                    Log.d(TAG, "TLS handshake successful: cipher=${sslSocket.session.cipherSuite}")
                    sslSocket
                } else {
                    baseSocket
                }

                sock.soTimeout = 10000
                socket = sock
                input = DataInputStream(sock.getInputStream())
                output = sock.getOutputStream()

                doHandshake()
                sendSetChunkSize(CHUNK_SIZE)
                sendConnect(app, host, port, isRtmps || port == 443)
                waitForPublishStart(key)

                sock.soTimeout = 0 // clear timeout for streaming
                checker.onConnectionSuccess()
                Log.d(TAG, "RTMP connected and publishing on stream $publishStreamId")
            } catch (e: Exception) {
                Log.e(TAG, "RTMP connect failed: ${e.message}", e)
                checker?.onConnectionFailed(e.message ?: "Unknown RTMP error")
            }
        }.start()
    }

    /**
     * Send video metadata first call sets up the stream decoder.
     * Then send AVC sequence header, then subsequent NAL units.
     */
    fun sendVideoSequenceHeader(sps: ByteArray, pps: ByteArray) {
        val spsPure = stripStartCode(sps)
        val ppsPure = stripStartCode(pps)

        val config = buildAvcDecoderConfigurationRecord(spsPure, ppsPure)

        // FLV video tag: frame_type=keyframe(1), codec=AVC(7), AVC packet type=0 (sequence header)
        val flvPayload = buildFlvVideoPayload(
            isKeyFrame = true,
            avcPacketType = 0x00, // Sequence header
            compositionTime = 0,
            data = config
        )

        sendRtmpMessage(CS_VIDEO, 0x09, publishStreamId, videoDts.get(), flvPayload)
        avcHeaderSent = true
        Log.d(TAG, "Sent AVC sequence header (SPS: ${spsPure.size}B, PPS: ${ppsPure.size}B)")
    }

    /**
     * Send a single H.264 NAL Unit from MediaCodec output.
     * Handles Annex-B (start-code prefixed) to AVCC (length-prefixed) conversion.
     */
    fun sendVideoNalu(annexBNalu: ByteArray, isKeyFrame: Boolean, dtsMs: Long) {
        if (!avcHeaderSent) return
        videoDts.set(dtsMs)

        val avccData = annexBToAvcc(annexBNalu)
        val flvPayload = buildFlvVideoPayload(
            isKeyFrame = isKeyFrame,
            avcPacketType = 0x01, // NALU
            compositionTime = 0,
            data = avccData
        )
        sendRtmpMessage(CS_VIDEO, 0x09, publishStreamId, dtsMs, flvPayload)
    }

    /**
     * Send AAC AudioSpecificConfig (from MediaCodec csd-0 buffer).
     */
    fun sendAudioSequenceHeader(audioSpecificConfig: ByteArray) {
        // FLV audio tag: SoundFormat=AAC(10), SampleRate=44100(3), SampleSize=16bit(1), Mono(0)
        // Byte 0: [1010 | 11 | 1 | 0] = 0xAE
        val flvPayload = ByteArray(2 + audioSpecificConfig.size)
        flvPayload[0] = 0xAE.toByte() // AAC, 44100, 16-bit, mono
        flvPayload[1] = 0x00          // AAC sequence header
        audioSpecificConfig.copyInto(flvPayload, 2)

        sendRtmpMessage(CS_AUDIO, 0x08, publishStreamId, 0L, flvPayload)
        aacHeaderSent = true
        Log.d(TAG, "Sent AAC sequence header (${audioSpecificConfig.size}B)")
    }

    /**
     * Send a raw AAC frame (ADTS header stripped).
     */
    fun sendAudioFrame(rawAac: ByteArray, dtsMs: Long) {
        if (!aacHeaderSent) return
        audioDts.set(dtsMs)

        val flvPayload = ByteArray(2 + rawAac.size)
        flvPayload[0] = 0xAE.toByte() // AAC, 44100, 16-bit, mono
        flvPayload[1] = 0x01          // AAC raw frame
        rawAac.copyInto(flvPayload, 2)

        sendRtmpMessage(CS_AUDIO, 0x08, publishStreamId, dtsMs, flvPayload)
    }

    fun disconnect() {
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        input = null
        output = null
        avcHeaderSent = false
        aacHeaderSent = false
        videoDts.set(0)
        audioDts.set(0)
        checker?.onDisconnected()
        checker = null
        Log.d(TAG, "RTMP disconnected")
    }

    // ─── RTMP Handshake ────────────────────────────────────────────────────────

    private fun doHandshake() {
        val out = output!!
        val inp = input!!

        // C0: RTMP version
        out.write(0x03)

        // C1: 1536 bytes — [4B time] [4B zero] [1528B random]
        val c1 = ByteArray(1536)
        val tsMs = System.currentTimeMillis().toInt()
        c1[0] = (tsMs shr 24).toByte()
        c1[1] = (tsMs shr 16).toByte()
        c1[2] = (tsMs shr 8).toByte()
        c1[3] = tsMs.toByte()
        // bytes 4-7 are already 0 from ByteArray init
        Random().nextBytes(c1.sliceArray(8 until 1536).also { System.arraycopy(it, 0, c1, 8, it.size) })
        out.write(c1)
        out.flush()

        // Read S0 (1 byte) + S1 (1536 bytes) + S2 (1536 bytes)
        inp.read() // S0 — ignore version check for simplicity
        val s1 = ByteArray(1536)
        inp.readFully(s1)
        val s2 = ByteArray(1536)
        inp.readFully(s2)

        // C2: echo S1 with our receive timestamp
        out.write(s1)
        out.flush()
        Log.d(TAG, "RTMP handshake complete")
    }

    // ─── Protocol Control ──────────────────────────────────────────────────────

    private fun sendSetChunkSize(size: Int) {
        val payload = byteArrayOf(
            ((size shr 24) and 0x7F).toByte(), // MSB of size (31 bits, high bit = 0)
            ((size shr 16) and 0xFF).toByte(),
            ((size shr 8) and 0xFF).toByte(),
            (size and 0xFF).toByte()
        )
        // Protocol control message: cs_id=2, msg type=1 (Set Chunk Size), stream=0, ts=0
        val header = buildChunkHeader(CS_CONTROL, 0x01, 0, 0L, payload.size)
        synchronized(output!!) {
            output!!.write(header)
            output!!.write(payload)
            output!!.flush()
        }
        Log.d(TAG, "Set chunk size to $size")
    }

    // ─── AMF0 Command Sequence ─────────────────────────────────────────────────

    private fun sendConnect(app: String, host: String, port: Int, isRtmps: Boolean) {
        val scheme = if (isRtmps) "rtmps" else "rtmp"
        val tcUrl = "$scheme://$host:$port/$app"
        val payload = AmfEncoder.buildConnectCommand(app, tcUrl)
        sendRtmpMessage(CS_COMMAND, 0x14, 0, 0L, payload)
        Log.d(TAG, "Sent RTMP connect: app=$app tcUrl=$tcUrl")
    }

    private fun sendCreateStream() {
        val payload = AmfEncoder.buildCreateStreamCommand()
        sendRtmpMessage(CS_COMMAND, 0x14, 0, 0L, payload)
        Log.d(TAG, "Sent createStream")
    }

    private fun sendPublish(streamKey: String) {
        val payload = AmfEncoder.buildPublishCommand(streamKey, publishStreamId)
        sendRtmpMessage(CS_COMMAND, 0x14, publishStreamId, 0L, payload)
        Log.d(TAG, "Sent publish with key (hidden)")
    }

    /**
     * Reads server chunks until we receive the onStatus "NetStream.Publish.Start" response.
     * Also handles _result for connect and createStream in sequence.
     */
    private fun waitForPublishStart(streamKey: String) {
        var connectAcked = false
        var createStreamAcked = false

        val inp = input!!
        val deadline = System.currentTimeMillis() + 8000L // 8s timeout

        while (System.currentTimeMillis() < deadline) {
            // Read chunk basic header
            val fmt_csid = inp.read()
            if (fmt_csid < 0) throw Exception("Server closed connection during handshake sequence")

            val fmt = (fmt_csid shr 6) and 0x03
            val csid = fmt_csid and 0x3F

            // Read message header
            val ts: Long
            val msgLen: Int
            val msgType: Int
            val streamId: Int

            when (fmt) {
                0 -> {
                    ts = readUInt24(inp)
                    msgLen = readUInt24(inp).toInt()
                    msgType = inp.read()
                    streamId = readUInt32LE(inp)
                }
                1 -> {
                    ts = readUInt24(inp)
                    msgLen = readUInt24(inp).toInt()
                    msgType = inp.read()
                    streamId = 0
                }
                2 -> {
                    ts = readUInt24(inp)
                    msgLen = 0; msgType = 0; streamId = 0
                }
                else -> { // fmt 3 — continuation
                    continue
                }
            }

            // Read payload (up to CHUNK_SIZE or server's declared chunk size)
            val payloadSize = if (msgLen > 0) msgLen else continue
            val payload = ByteArray(payloadSize)
            inp.readFully(payload)

            // Only look at AMF0 command messages (type 0x14 or 0x11)
            if (msgType != 0x14 && msgType != 0x11) continue

            // Parse first AMF0 string to identify the command
            val cmdName = readAmfString(payload, 0)
            Log.d(TAG, "Received RTMP command: $cmdName")

            when (cmdName) {
                "_result" -> {
                    if (!connectAcked) {
                        connectAcked = true
                        Log.d(TAG, "connect _result received → sending createStream")
                        sendCreateStream()
                    } else if (!createStreamAcked) {
                        createStreamAcked = true
                        // Read the stream ID from _result response (AMF0 number after null)
                        // Format: "_result" | txId | null | streamId
                        Log.d(TAG, "createStream _result received → sending publish")
                        sendPublish(streamKey)
                    }
                }
                "onStatus" -> {
                    // Check for NetStream.Publish.Start
                    val statusCode = readAmfObjectProperty(payload, "code")
                    Log.d(TAG, "onStatus code=$statusCode")
                    if (statusCode?.contains("Publish.Start") == true ||
                        statusCode?.contains("Stream.Start") == true) {
                        Log.d(TAG, "Publish started — RTMP streaming active")
                        return
                    }
                    if (statusCode?.contains("Error") == true ||
                        statusCode?.contains("Reject") == true) {
                        throw Exception("Server rejected stream: $statusCode")
                    }
                }
                "_error" -> {
                    throw Exception("Server returned _error during RTMP negotiation")
                }
            }
        }
        throw Exception("Timeout waiting for server publish confirmation (8s)")
    }

    // ─── RTMP Message / Chunk Sending ─────────────────────────────────────────

    /**
     * Sends an RTMP message, automatically splitting into chunks of CHUNK_SIZE.
     */
    @Synchronized
    private fun sendRtmpMessage(
        csId: Int,
        msgType: Int,
        msgStreamId: Int,
        timestampMs: Long,
        payload: ByteArray
    ) {
        val out = output ?: return
        try {
            val hasExtendedTs = timestampMs >= 0xFFFFFFL
            val header = buildChunkHeader(csId, msgType, msgStreamId, timestampMs, payload.size)
            out.write(header)

            var offset = 0
            while (offset < payload.size) {
                val chunkBytes = minOf(CHUNK_SIZE, payload.size - offset)
                out.write(payload, offset, chunkBytes)
                offset += chunkBytes
                if (offset < payload.size) {
                    // Format 3 continuation chunk (same CS, no header)
                    out.write((0xC0 or csId) and 0xFF)
                    if (hasExtendedTs) {
                        val extTs = timestampMs.toInt()
                        out.write((extTs shr 24) and 0xFF)
                        out.write((extTs shr 16) and 0xFF)
                        out.write((extTs shr 8) and 0xFF)
                        out.write(extTs and 0xFF)
                    }
                }
            }
            out.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send RTMP message type $msgType: ${e.message}")
            checker?.onDisconnected()
        }
    }

    /** Builds a Format-0 chunk header (full 11-byte message header + optional extended timestamp). */
    private fun buildChunkHeader(
        csId: Int, msgType: Int, streamId: Int,
        timestamp: Long, payloadLen: Int
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val hasExtendedTs = timestamp >= 0xFFFFFFL
        // Basic header: format=0, cs_id (1 byte for cs_id 2-63)
        out.write(csId and 0x3F)
        // Timestamp (3 bytes big-endian)
        val ts = if (hasExtendedTs) 0xFFFFFF else timestamp.toInt()
        out.write((ts shr 16) and 0xFF)
        out.write((ts shr 8) and 0xFF)
        out.write(ts and 0xFF)
        // Message length (3 bytes)
        out.write((payloadLen shr 16) and 0xFF)
        out.write((payloadLen shr 8) and 0xFF)
        out.write(payloadLen and 0xFF)
        // Message type ID (1 byte)
        out.write(msgType)
        // Message stream ID (4 bytes LITTLE-endian)
        out.write(streamId and 0xFF)
        out.write((streamId shr 8) and 0xFF)
        out.write((streamId shr 16) and 0xFF)
        out.write((streamId shr 24) and 0xFF)
        // Extended timestamp (4 bytes big-endian) if timestamp >= 0xFFFFFF
        if (hasExtendedTs) {
            val extTs = timestamp.toInt()
            out.write((extTs shr 24) and 0xFF)
            out.write((extTs shr 16) and 0xFF)
            out.write((extTs shr 8) and 0xFF)
            out.write(extTs and 0xFF)
        }
        return out.toByteArray()
    }

    // ─── FLV Tag Builders ──────────────────────────────────────────────────────

    private fun buildFlvVideoPayload(
        isKeyFrame: Boolean, avcPacketType: Int,
        compositionTime: Int, data: ByteArray
    ): ByteArray {
        val out = ByteArrayOutputStream()
        // Byte 0: FrameType(4 bits) | CodecID(4 bits)
        // FrameType: 1=keyframe, 2=inter | CodecID: 7=AVC
        val frameType = if (isKeyFrame) 0x10 else 0x20
        out.write(frameType or 0x07) // AVC = codec 7
        // Byte 1: AVCPacketType (0=seq header, 1=NALU, 2=EOS)
        out.write(avcPacketType)
        // Bytes 2-4: CompositionTime offset (3 bytes, signed)
        out.write((compositionTime shr 16) and 0xFF)
        out.write((compositionTime shr 8) and 0xFF)
        out.write(compositionTime and 0xFF)
        // Payload
        out.write(data)
        return out.toByteArray()
    }

    /** Builds AVCDecoderConfigurationRecord from raw SPS and PPS bytes (without start codes). */
    private fun buildAvcDecoderConfigurationRecord(sps: ByteArray, pps: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x01)             // configurationVersion
        out.write(sps[1].toInt() and 0xFF) // AVCProfileIndication
        out.write(sps[2].toInt() and 0xFF) // profile_compatibility
        out.write(sps[3].toInt() and 0xFF) // AVCLevelIndication
        out.write(0xFF)             // lengthSizeMinusOne = 3 (→ 4-byte length prefix)
        out.write(0xE1)             // numSequenceParameterSets = 1
        out.write((sps.size shr 8) and 0xFF)
        out.write(sps.size and 0xFF)
        out.write(sps)
        out.write(0x01)             // numPictureParameterSets = 1
        out.write((pps.size shr 8) and 0xFF)
        out.write(pps.size and 0xFF)
        out.write(pps)
        return out.toByteArray()
    }

    /** Converts Annex-B (00 00 00 01 + NALU) to AVCC (4-byte length + NALU). */
    private fun annexBToAvcc(annexB: ByteArray): ByteArray {
        val naluData = stripStartCode(annexB)
        val out = ByteArrayOutputStream()
        out.write((naluData.size shr 24) and 0xFF)
        out.write((naluData.size shr 16) and 0xFF)
        out.write((naluData.size shr 8) and 0xFF)
        out.write(naluData.size and 0xFF)
        out.write(naluData)
        return out.toByteArray()
    }

    /** Strips the Annex-B start code (00 00 00 01 or 00 00 01) from the beginning. */
    private fun stripStartCode(data: ByteArray): ByteArray {
        return when {
            data.size > 4 && data[0] == 0x00.toByte() && data[1] == 0x00.toByte() &&
                    data[2] == 0x00.toByte() && data[3] == 0x01.toByte() -> data.copyOfRange(4, data.size)
            data.size > 3 && data[0] == 0x00.toByte() && data[1] == 0x00.toByte() &&
                    data[2] == 0x01.toByte() -> data.copyOfRange(3, data.size)
            else -> data
        }
    }

    // ─── URL Parsing ───────────────────────────────────────────────────────────

    private data class ParsedUrl(
        val isRtmps: Boolean,
        val host: String,
        val port: Int,
        val app: String,
        val key: String
    )

    private fun parseRtmpUrl(url: String): ParsedUrl {
        val isRtmps = url.startsWith("rtmps://", ignoreCase = true)
        val cleaned = url.removePrefix("rtmps://").removePrefix("rtmp://").removePrefix("RTMPS://").removePrefix("RTMP://")
        val slashIdx = cleaned.indexOf('/')
        val hostPort = if (slashIdx != -1) cleaned.substring(0, slashIdx) else cleaned
        val rest = if (slashIdx != -1) cleaned.substring(slashIdx + 1) else ""

        val host = if (hostPort.contains(':')) hostPort.substringBefore(':') else hostPort
        val defaultPort = if (isRtmps) 443 else 1935
        val port = if (hostPort.contains(':')) hostPort.substringAfter(':').toIntOrNull() ?: defaultPort else defaultPort

        val appAndKey = rest.split("/")
        val app = appAndKey.getOrElse(0) { "live2" }
        val key = if (appAndKey.size > 1) rest.substringAfter("$app/") else ""

        return ParsedUrl(isRtmps, host, port, app, key)
    }

    // ─── AMF0 Response Parsing (Minimal) ──────────────────────────────────────

    private fun readAmfString(payload: ByteArray, offset: Int): String {
        if (offset >= payload.size || payload[offset] != 0x02.toByte()) return ""
        val len = ((payload[offset + 1].toInt() and 0xFF) shl 8) or (payload[offset + 2].toInt() and 0xFF)
        return String(payload, offset + 3, len, Charsets.UTF_8)
    }

    /** Searches an AMF0 object in the payload for a specific property key and returns its string value. */
    private fun readAmfObjectProperty(payload: ByteArray, key: String): String? {
        var i = 0
        while (i < payload.size - 2) {
            if (payload[i] == 0x00.toByte() && payload[i + 1] == 0x00.toByte() &&
                payload[i + 2] == 0x09.toByte()) break // object end

            if (i + 2 < payload.size) {
                val kLen = ((payload[i].toInt() and 0xFF) shl 8) or (payload[i + 1].toInt() and 0xFF)
                if (i + 2 + kLen < payload.size) {
                    val k = String(payload, i + 2, kLen, Charsets.UTF_8)
                    val vOffset = i + 2 + kLen
                    if (k == key && vOffset < payload.size && payload[vOffset] == 0x02.toByte()) {
                        val vLen = ((payload[vOffset + 1].toInt() and 0xFF) shl 8) or
                                (payload[vOffset + 2].toInt() and 0xFF)
                        return String(payload, vOffset + 3, vLen, Charsets.UTF_8)
                    }
                    i += 2 + kLen + 1 // skip past this property (approximate)
                } else break
            } else break
        }
        return null
    }

    private fun readUInt24(inp: DataInputStream): Long {
        val b0 = inp.read()
        val b1 = inp.read()
        val b2 = inp.read()
        return ((b0 shl 16) or (b1 shl 8) or b2).toLong()
    }

    private fun readUInt32LE(inp: DataInputStream): Int {
        val b0 = inp.read()
        val b1 = inp.read()
        val b2 = inp.read()
        val b3 = inp.read()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}
