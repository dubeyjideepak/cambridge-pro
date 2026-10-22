package com.cambridge.streamer.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.IOException
import java.nio.ByteBuffer

class HardwareVideoEncoder(
    private val width: Int = 1920,
    private val height: Int = 1080,
    private val bitrate: Int = 4_500_000, // 4.5 Mbps
    private val frameRate: Int = 30,
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
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second GOP for fast OBS sync
                // Low latency mode — not supported on all Qualcomm/MediaTek SoCs; safe-guard with try/catch
                try { setInteger(MediaFormat.KEY_LATENCY, 0) } catch (_: Exception) {}
                try { setInteger(MediaFormat.KEY_PRIORITY, 0) } catch (_: Exception) {} // Realtime priority
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
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
                Log.d(TAG, "Encoder output format changed. SPS/PPS captured.")
            } else if (outputBufferIndex >= 0) {
                val outputBuffer: ByteBuffer = codec.getOutputBuffer(outputBufferIndex) ?: continue

                if (bufferInfo.size > 0) {
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                    val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    val frameBytes = ByteArray(bufferInfo.size)
                    outputBuffer.get(frameBytes)
                    val ptsMs = bufferInfo.presentationTimeUs / 1000L

                    onEncodedFrame(frameBytes, isKeyFrame, ptsMs)
                }

                codec.releaseOutputBuffer(outputBufferIndex, false)
            }
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
