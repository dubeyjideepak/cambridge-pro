package com.cambridge.streamer.stream

import android.util.Log
import com.cambridge.streamer.audio.AudioCapturePipeline

/**
 * Production RTMP Stream Manager
 *
 * Orchestrates the full publish pipeline:
 *   HardwareVideoEncoder NALUs
 *     → RtmpClient (FLV packaging + RTMP chunking → YouTube / Facebook / Instagram)
 *   AudioCapturePipeline AAC frames
 *     → RtmpClient (FLV audio tag → RTMP)
 *
 * Usage:
 *   1. Call [startLiveStream] with the full RTMP URL and initial SPS/PPS bytes.
 *   2. Once [onConnected] fires, call [pushVideoFrame] for every encoder NALU.
 *   3. Call [stopLiveStream] to tear down.
 *
 * Supported platforms:
 *   YouTube Live  →  rtmp://a.rtmp.youtube.com/live2/{key}
 *   Facebook Live →  rtmp://live-api-s.facebook.com:80/rtmp/{key}
 *   Instagram     →  rtmp://edgetee-upload-{dc}.facebook.com:80/rtmp/{key}
 *   Custom RTMP   →  any rtmp:// endpoint
 */
class RtmpStreamManager(private val context: android.content.Context? = null) {

    private val TAG = "RtmpStreamManager"

    private val rtmpClient = RtmpClient()
    private var audioPipeline: AudioCapturePipeline? = null

    @Volatile var isLive: Boolean = false
        private set

    // Cached SPS/PPS — may arrive slightly before or after connect ACK
    @Volatile private var pendingSps: ByteArray? = null
    @Volatile private var pendingPps: ByteArray? = null
    @Volatile private var avcHeaderQueued = false

    // ─── Public API ────────────────────────────────────────────────────────────

    /**
     * Connect to the RTMP server and begin publishing.
     *
     * @param rtmpUrl Full RTMP URL including stream key
     *                e.g. "rtmp://a.rtmp.youtube.com/live2/xxxx-xxxx-xxxx"
     * @param sps     SPS NAL unit (Annex-B, as from MediaCodec csd-0)
     * @param pps     PPS NAL unit (Annex-B, as from MediaCodec csd-1)
     * @param onConnected Called on the RTMP thread once publish is confirmed
     * @param onError     Called on error with a human-readable reason string
     */
    fun startLiveStream(
        rtmpUrl: String,
        sps: ByteArray?,
        pps: ByteArray?,
        onConnected: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (isLive) {
            Log.w(TAG, "startLiveStream called while already live — ignoring")
            return
        }

        pendingSps = sps
        pendingPps = pps
        avcHeaderQueued = false

        Log.d(TAG, "Connecting RTMP: ${rtmpUrl.take(50)}…")

        rtmpClient.connect(rtmpUrl, object : RtmpClient.ConnectChecker {

            override fun onConnectionSuccess() {
                isLive = true
                Log.d(TAG, "RTMP publish confirmed — starting A/V feed")

                // Send AVC decoder configuration record (SPS + PPS)
                val spsNow = pendingSps
                val ppsNow = pendingPps
                if (spsNow != null && ppsNow != null) {
                    rtmpClient.sendVideoSequenceHeader(spsNow, ppsNow)
                    avcHeaderQueued = true
                } else {
                    Log.w(TAG, "SPS/PPS not ready at connect — will send on first keyframe")
                }

                // Start audio capture + send AAC sequence header
                startAudioPipeline()

                onConnected()
            }

            override fun onConnectionFailed(reason: String) {
                isLive = false
                Log.e(TAG, "RTMP connection failed: $reason")
                onError(reason)
            }

            override fun onDisconnected() {
                isLive = false
                Log.w(TAG, "RTMP disconnected")
                audioPipeline?.stop()
                audioPipeline = null
                onError("Connection lost")
            }
        })
    }

    /**
     * Push a H.264 NALU from the encoder into the RTMP stream.
     *
     * If we haven't sent the AVC sequence header yet (rare race: SPS/PPS
     * arrived after [startLiveStream] was called), we send it now on the first keyframe.
     *
     * @param h264Nalu   Raw NALU bytes from MediaCodec (Annex-B with start code)
     * @param isKeyFrame true for IDR frames
     * @param dtsMs      Decode timestamp in milliseconds (System.currentTimeMillis() basis)
     */
    // Stream clock reference & Lip-sync synchronization
    @Volatile private var streamStartTimeMs: Long = 0L

    /**
     * Audio delay compensation offset in milliseconds.
     * HDMI capture cards buffer and process video with ~80ms-120ms latency.
     * Defaulting to +100ms ensures perfect lip-sync out of the box.
     */
    @Volatile var audioDelayOffsetMs: Long = 100L

    fun pushVideoFrame(h264Nalu: ByteArray, isKeyFrame: Boolean, ptsMs: Long = -1L) {
        if (!isLive) return

        val currentClockMs = if (ptsMs > 0) ptsMs else System.currentTimeMillis()
        if (streamStartTimeMs == 0L) {
            streamStartTimeMs = currentClockMs
        }

        // Late-arriving SPS/PPS: send on first keyframe
        if (!avcHeaderQueued && isKeyFrame) {
            val spsNow = pendingSps
            val ppsNow = pendingPps
            if (spsNow != null && ppsNow != null) {
                rtmpClient.sendVideoSequenceHeader(spsNow, ppsNow)
                avcHeaderQueued = true
                Log.d(TAG, "AVC sequence header sent on first keyframe (late SPS/PPS)")
            }
        }

        val relativeVideoDts = maxOf(0L, currentClockMs - streamStartTimeMs)
        rtmpClient.sendVideoNalu(h264Nalu, isKeyFrame, relativeVideoDts)
    }

    /**
     * Update SPS/PPS mid-stream (e.g. if encoder reconfigures resolution).
     * Will send a new AVC sequence header on the next keyframe.
     */
    fun updateSpsPps(sps: ByteArray, pps: ByteArray) {
        pendingSps = sps
        pendingPps = pps
        if (isLive) {
            rtmpClient.sendVideoSequenceHeader(sps, pps)
            Log.d(TAG, "AVC sequence header refreshed (resolution change)")
        }
    }

    fun stopLiveStream() {
        isLive = false
        streamStartTimeMs = 0L
        audioPipeline?.stop()
        audioPipeline = null
        rtmpClient.disconnect()
        pendingSps = null
        pendingPps = null
        avcHeaderQueued = false
        Log.d(TAG, "RTMP stream stopped")
    }

    // ─── Audio Pipeline ────────────────────────────────────────────────────────

    private fun startAudioPipeline() {
        audioPipeline = AudioCapturePipeline(
            context = context,
            sampleRate = 48000,
            channelConfig = android.media.AudioFormat.CHANNEL_IN_MONO,
            bitrate = 128_000,
            onAudioSpecificConfig = { asc ->
                // Send AAC sequence header (AudioSpecificConfig from csd-0)
                rtmpClient.sendAudioSequenceHeader(asc)
                Log.d(TAG, "AAC sequence header sent")
            },
            onAudioFrame = { rawAac, dtsMs ->
                if (isLive) {
                    val currentClockMs = if (dtsMs > 0) dtsMs else System.currentTimeMillis()
                    if (streamStartTimeMs == 0L) {
                        streamStartTimeMs = currentClockMs
                    }
                    val elapsedMs = maxOf(0L, currentClockMs - streamStartTimeMs)
                    val synchronizedAudioDts = maxOf(0L, elapsedMs + audioDelayOffsetMs)
                    rtmpClient.sendAudioFrame(rawAac, synchronizedAudioDts)
                }
            }
        )
        audioPipeline?.start()
    }
}
