package com.cambridge.streamer.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.io.IOException
import java.nio.ByteBuffer

class HardwareVideoEncoder(
    private val width: Int = 1920,
    private val height: Int = 1080,
    private val bitrate: Int = 4_500_000, // 4.5 Mbps
    private val frameRate: Int = 60,
    private val onEncodedFrame: (frameBytes: ByteArray, isKeyFrame: Boolean, ptsMs: Long) -> Unit
) {
    private val TAG = "HardwareVideoEncoder"
    private var mediaCodec: MediaCodec? = null
    var inputSurface: Surface? = null
        private set

    @Volatile
    private var isEncoding = false
    private var workerThread: Thread? = null

    var sps: ByteArray? = null
        private set
    var pps: ByteArray? = null
        private set

    fun start() {
        if (isEncoding) return

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second GOP
                // Variable Bitrate (VBR): prevents hardware encoder rate-control from dropping frames during sudden motion bursts
                try {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                } catch (_: Exception) {}

                // Baseline Profile (strictly 0 B-frames, zero encoder lookahead delay)
                try {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
                } catch (_: Exception) {}

                try { setInteger("max-bframes", 0) } catch (_: Exception) {}
                // Qualcomm Snapdragon low-latency vendor extension
                try { setInteger("vendor.qcom-ext-enc-low-latency.enable", 1) } catch (_: Exception) {}
                try { setInteger(MediaFormat.KEY_LATENCY, 0) } catch (_: Exception) {}
                try { setInteger(MediaFormat.KEY_PRIORITY, 0) } catch (_: Exception) {} // Realtime priority
            }

            // Explicitly query MediaCodecList for Qualcomm / hardware vendor encoder to avoid Google software fallback
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val hwEncoderInfo = codecList.codecInfos.firstOrNull { info ->
                info.isEncoder &&
                info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } &&
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated && !info.isSoftwareOnly else !info.name.startsWith("OMX.google."))
            }

            val codec = if (hwEncoderInfo != null) {
                Log.d(TAG, "Selected dedicated hardware encoder: ${hwEncoderInfo.name}")
                MediaCodec.createByCodecName(hwEncoderInfo.name)
            } else {
                Log.d(TAG, "Using default AVC encoder")
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            }

            mediaCodec = codec.apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
                start()
            }

            isEncoding = true
            workerThread = Thread { drainEncoder() }.apply { start() }
            Log.d(TAG, "Hardware H.264 Encoder started ($width x $height @ ${bitrate / 1000} Kbps)")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start MediaCodec", e)
        }
    }

    private fun drainEncoder() {
        val codec = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (isEncoding) {
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)

            if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = codec.outputFormat
                val csd0 = newFormat.getByteBuffer("csd-0") // SPS
                val csd1 = newFormat.getByteBuffer("csd-1") // PPS

                if (csd0 != null) {
                    sps = ByteArray(csd0.remaining()).also { csd0.get(it) }
                }
                if (csd1 != null) {
                    pps = ByteArray(csd1.remaining()).also { csd1.get(it) }
                }
                Log.d(TAG, "Encoder output format changed. SPS/PPS captured (sps=${sps?.size}B, pps=${pps?.size}B).")
            } else if (outputBufferIndex >= 0) {
                val outputBuffer: ByteBuffer = codec.getOutputBuffer(outputBufferIndex) ?: continue

                if (bufferInfo.size > 0) {
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                    val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    val frameBytes = ByteArray(bufferInfo.size)
                    outputBuffer.get(frameBytes)
                    val ptsMs = bufferInfo.presentationTimeUs / 1000L

                    // Fallback: extract SPS/PPS directly from bitstream if format change didn't provide them
                    if (sps == null || pps == null || isCodecConfig) {
                        extractSpsPpsFromBytes(frameBytes)
                    }

                    if (!isCodecConfig) {
                        onEncodedFrame(frameBytes, isKeyFrame, ptsMs)
                    }
                }

                codec.releaseOutputBuffer(outputBufferIndex, false)
            }
        }
    }

    private fun extractSpsPpsFromBytes(data: ByteArray) {
        var i = 0
        val len = data.size
        val nalOffsets = mutableListOf<Pair<Int, Int>>() // Pair(startPos, prefixLen)

        while (i < len - 3) {
            val is4Byte = data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            val is3Byte = data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 1.toByte()

            if (is4Byte || is3Byte) {
                val prefix = if (is4Byte) 4 else 3
                nalOffsets.add(Pair(i + prefix, prefix))
                i += prefix
            } else {
                i++
            }
        }

        for (idx in nalOffsets.indices) {
            val start = nalOffsets[idx].first
            val end = if (idx + 1 < nalOffsets.size) nalOffsets[idx + 1].first - nalOffsets[idx + 1].second else len
            if (start < len && end > start) {
                val nalType = data[start].toInt() and 0x1F
                val nalBytes = data.copyOfRange(start, end)
                if (nalType == 7 && sps == null) {
                    sps = nalBytes
                    Log.d(TAG, "Extracted SPS (${nalBytes.size} bytes) from NAL stream")
                } else if (nalType == 8 && pps == null) {
                    pps = nalBytes
                    Log.d(TAG, "Extracted PPS (${nalBytes.size} bytes) from NAL stream")
                }
            }
        }
    }

    /**
     * Request the hardware encoder to emit an immediate IDR keyframe (sync frame)
     * with fresh SPS/PPS for instant OBS/player sync.
     */
    fun requestSyncFrame() {
        try {
            val params = android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            mediaCodec?.setParameters(params)
            Log.d(TAG, "Requested sync frame from MediaCodec")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request sync frame", e)
        }
    }

    fun stop() {
        isEncoding = false
        try {
            workerThread?.join(500)
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
            inputSurface = null
            Log.d(TAG, "Hardware H.264 Encoder stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping encoder", e)
        }
    }
}
