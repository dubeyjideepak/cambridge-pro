package com.cambridge.streamer.stream

import android.content.Context
import android.util.Log
import com.cambridge.streamer.audio.AudioCapturePipeline
import java.util.concurrent.ConcurrentHashMap

/**
 * Data structure representing an individual streaming endpoint.
 */
data class LiveDestination(
    val id: String,          // e.g. "youtube", "facebook", "instagram", "custom"
    val label: String,       // e.g. "YouTube Live", "Facebook Live", "Instagram Live"
    val rtmpUrl: String,
    val isEnabled: Boolean = true
)

/**
 * Production Multi-Platform RTMP Stream Manager
 *
 * Encodes once via Qualcomm hardware encoder and fans out identical H.264 NALUs
 * and AAC audio frames across multiple live platforms simultaneously:
 * - YouTube Live (RTMP port 1935)
 * - Facebook Live (RTMPS port 443 TLS)
 * - Instagram Live (RTMPS port 443 TLS via Live Producer)
 * - Custom RTMP server
 */
class RtmpStreamManager(private val context: Context? = null) {

    private val TAG = "RtmpStreamManager"

    private val clients = ConcurrentHashMap<String, RtmpClient>()
    private val activeDestinations = ConcurrentHashMap<String, LiveDestination>()
    private val connectedStates = ConcurrentHashMap<String, Boolean>()

    private var audioPipeline: AudioCapturePipeline? = null

    @Volatile var isLive: Boolean = false
        private set

    // Cached SPS/PPS — may arrive slightly before or after connect ACK
    @Volatile private var pendingSps: ByteArray? = null
    @Volatile private var pendingPps: ByteArray? = null
    @Volatile private var cachedAudioSpecificConfig: ByteArray? = null
    private val avcHeadersSent = ConcurrentHashMap<String, Boolean>()

    // Stream clock reference & Lip-sync synchronization
    @Volatile private var streamStartTimeMs: Long = 0L

    /**
     * Audio delay compensation offset in milliseconds.
     * Default +100ms compensates for HDMI capture card video pipeline latency.
     */
    @Volatile var audioDelayOffsetMs: Long = 100L

    // ─── Public API ────────────────────────────────────────────────────────────

    /**
     * Connect to multiple RTMP/RTMPS destinations in parallel and begin publishing.
     */
    fun startMultiStream(
        destinations: List<LiveDestination>,
        sps: ByteArray?,
        pps: ByteArray?,
        onTargetConnected: (id: String, label: String) -> Unit,
        onTargetFailed: (id: String, label: String, error: String) -> Unit,
        onAllDisconnected: () -> Unit
    ) {
        val enabledDests = destinations.filter { it.isEnabled && it.rtmpUrl.isNotBlank() }
        if (enabledDests.isEmpty()) {
            Log.w(TAG, "No enabled RTMP destinations provided")
            onAllDisconnected()
            return
        }

        stopLiveStream() // Clean up any active connections

        pendingSps = sps
        pendingPps = pps
        streamStartTimeMs = 0L
        isLive = true

        for (dest in enabledDests) {
            activeDestinations[dest.id] = dest
            val client = RtmpClient()
            clients[dest.id] = client

            Log.d(TAG, "Connecting to [${dest.label}]: ${dest.rtmpUrl.take(45)}…")

            client.connect(dest.rtmpUrl, object : RtmpClient.ConnectChecker {
                override fun onConnectionSuccess() {
                    Log.d(TAG, "RTMP connection established to [${dest.label}]")
                    connectedStates[dest.id] = true

                    // Send AVC sequence header
                    val spsNow = pendingSps
                    val ppsNow = pendingPps
                    if (spsNow != null && ppsNow != null) {
                        client.sendVideoSequenceHeader(spsNow, ppsNow)
                        avcHeadersSent[dest.id] = true
                        Log.d(TAG, "Sent AVC sequence header to [${dest.label}]")
                    }

                    // Start audio capture once across all clients
                    synchronized(this@RtmpStreamManager) {
                        if (audioPipeline == null) {
                            startAudioPipeline()
                        } else {
                            // If audio pipeline is already running, immediately send cached ASC to this client
                            cachedAudioSpecificConfig?.let { asc ->
                                client.sendAudioSequenceHeader(asc)
                            }
                        }
                    }

                    onTargetConnected(dest.id, dest.label)
                }

                override fun onConnectionFailed(reason: String) {
                    Log.e(TAG, "RTMP connection failed for [${dest.label}]: $reason")
                    connectedStates[dest.id] = false
                    onTargetFailed(dest.id, dest.label, reason)
                    checkAllDisconnected(onAllDisconnected)
                }

                override fun onDisconnected() {
                    Log.w(TAG, "RTMP disconnected from [${dest.label}]")
                    connectedStates[dest.id] = false
                    onTargetFailed(dest.id, dest.label, "Connection lost")
                    checkAllDisconnected(onAllDisconnected)
                }
            })
        }
    }

    private fun checkAllDisconnected(onAllDisconnected: () -> Unit) {
        val anyLive = connectedStates.values.any { it }
        if (!anyLive && isLive) {
            isLive = false
            audioPipeline?.stop()
            audioPipeline = null
            onAllDisconnected()
        }
    }

    /**
     * Push a H.264 NALU from the hardware encoder to all active live platforms.
     */
    fun pushVideoFrame(h264Nalu: ByteArray, isKeyFrame: Boolean, ptsMs: Long = -1L) {
        if (!isLive) return

        val currentClockMs = if (ptsMs > 0) ptsMs else System.currentTimeMillis()
        if (streamStartTimeMs == 0L) {
            streamStartTimeMs = currentClockMs
        }
        val relativeVideoDts = maxOf(0L, currentClockMs - streamStartTimeMs)

        val spsNow = pendingSps
        val ppsNow = pendingPps

        for ((id, client) in clients) {
            if (connectedStates[id] == true) {
                // Send late-arriving SPS/PPS on first keyframe if not sent yet
                if (avcHeadersSent[id] != true && isKeyFrame && spsNow != null && ppsNow != null) {
                    client.sendVideoSequenceHeader(spsNow, ppsNow)
                    avcHeadersSent[id] = true
                }
                client.sendVideoNalu(h264Nalu, isKeyFrame, relativeVideoDts)
            }
        }
    }

    /**
     * Update SPS/PPS mid-stream (e.g. if resolution reconfigures).
     */
    fun updateSpsPps(sps: ByteArray, pps: ByteArray) {
        pendingSps = sps
        pendingPps = pps
        if (isLive) {
            for ((id, client) in clients) {
                if (connectedStates[id] == true) {
                    client.sendVideoSequenceHeader(sps, pps)
                }
            }
        }
    }

    fun stopLiveStream() {
        isLive = false
        streamStartTimeMs = 0L
        audioPipeline?.stop()
        audioPipeline = null

        for ((_, client) in clients) {
            try {
                client.disconnect()
            } catch (ignored: Exception) {}
        }

        clients.clear()
        activeDestinations.clear()
        connectedStates.clear()
        avcHeadersSent.clear()
        pendingSps = null
        pendingPps = null
        cachedAudioSpecificConfig = null
        Log.d(TAG, "Multi-platform live stream stopped")
    }

    // ─── Audio Pipeline ────────────────────────────────────────────────────────

    private fun startAudioPipeline() {
        audioPipeline = AudioCapturePipeline(
            context = context,
            sampleRate = 48000,
            channelConfig = android.media.AudioFormat.CHANNEL_IN_MONO,
            bitrate = 128_000,
            onAudioSpecificConfig = { asc ->
                cachedAudioSpecificConfig = asc
                for ((id, client) in clients) {
                    if (connectedStates[id] == true) {
                        client.sendAudioSequenceHeader(asc)
                    }
                }
                Log.d(TAG, "AAC sequence header sent to all connected clients")
            },
            onAudioFrame = { rawAac, dtsMs ->
                if (isLive) {
                    val currentClockMs = if (dtsMs > 0) dtsMs else System.currentTimeMillis()
                    if (streamStartTimeMs == 0L) {
                        streamStartTimeMs = currentClockMs
                    }
                    val elapsedMs = maxOf(0L, currentClockMs - streamStartTimeMs)
                    val synchronizedAudioDts = maxOf(0L, elapsedMs + audioDelayOffsetMs)

                    for ((id, client) in clients) {
                        if (connectedStates[id] == true) {
                            client.sendAudioFrame(rawAac, synchronizedAudioDts)
                        }
                    }
                }
            }
        )
        audioPipeline?.start()
    }

    // Backward-compatibility single stream entry point
    fun startLiveStream(
        rtmpUrl: String,
        sps: ByteArray?,
        pps: ByteArray?,
        onConnected: () -> Unit,
        onError: (String) -> Unit
    ) {
        startMultiStream(
            destinations = listOf(LiveDestination("single", "Live", rtmpUrl, true)),
            sps = sps,
            pps = pps,
            onTargetConnected = { _, _ -> onConnected() },
            onTargetFailed = { _, _, err -> onError(err) },
            onAllDisconnected = { onError("Connection failed") }
        )
    }
}
