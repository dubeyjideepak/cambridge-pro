package com.cambridge.streamer.stream

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import com.pedro.common.ConnectChecker
import com.pedro.library.srt.SrtStream
import com.pedro.library.util.sources.audio.NoAudioSource
import com.pedro.library.util.sources.video.VideoSource

/**
 * SRT (Secure Reliable Transport) streaming engine for direct OBS Studio integration.
 * Uses hardware MediaCodec H.264 encoding and ARQ UDP packet recovery.
 * Zero-freeze even across congested Wi-Fi bands.
 */
class SrtBridgeEngine(
    private val context: Context,
    private val listener: SrtListener
) : ConnectChecker {

    interface SrtListener {
        fun onSrtConnected()
        fun onSrtDisconnected()
        fun onSrtFailed(reason: String)
        fun onSrtSurfaceReady(surface: Surface)
        fun onSrtSurfaceDestroyed(surface: Surface)
    }

    private val TAG = "SrtBridgeEngine"
    private var srtStream: SrtStream? = null
    private var customVideoSource: SrtVideoSource? = null
    var isStreaming = false
        private set

    inner class SrtVideoSource : VideoSource() {
        var inputSurface: Surface? = null
            private set
        private var isRunning = false

        override fun create(width: Int, height: Int, fps: Int, rotation: Int): Boolean = true

        override fun start(surfaceTexture: SurfaceTexture) {
            surfaceTexture.setDefaultBufferSize(width, height)
            val s = Surface(surfaceTexture)
            inputSurface = s
            isRunning = true
            listener.onSrtSurfaceReady(s)
            Log.d(TAG, "SrtVideoSource started: ${width}x${height} surface ready")
        }

        override fun stop() {
            isRunning = false
            inputSurface?.let {
                listener.onSrtSurfaceDestroyed(it)
                it.release()
            }
            inputSurface = null
            Log.d(TAG, "SrtVideoSource stopped")
        }

        override fun release() {
            stop()
        }

        override fun isRunning(): Boolean = isRunning
    }

    fun prepare(width: Int = 1920, height: Int = 1080, fps: Int = 60, bitrateKbps: Int = 8000): Boolean {
        release()
        val videoSource = SrtVideoSource().also { customVideoSource = it }
        val stream = SrtStream(context, this, videoSource, NoAudioSource())
        srtStream = stream

        val prepared = stream.prepareVideo(
            width,
            height,
            fps,
            bitrateKbps * 1024,
            1, // 1 second GOP
            0  // Rotation
        )

        Log.d(TAG, "prepareVideo result: $prepared ($width x $height @ $fps fps, $bitrateKbps Kbps)")
        return prepared
    }

    fun startStream(url: String) {
        if (srtStream == null) return
        Log.i(TAG, "Starting SRT stream to $url")
        srtStream?.startStream(url)
    }

    fun stopStream() {
        srtStream?.stopStream()
        isStreaming = false
    }

    fun release() {
        stopStream()
        srtStream?.release()
        srtStream = null
        customVideoSource = null
    }

    override fun onConnectionStarted(url: String) {
        Log.d(TAG, "SRT connection started: $url")
    }

    override fun onConnectionSuccess() {
        Log.i(TAG, "✅ SRT connected successfully to OBS")
        isStreaming = true
        listener.onSrtConnected()
    }

    override fun onConnectionFailed(reason: String) {
        Log.e(TAG, "❌ SRT connection failed: $reason")
        isStreaming = false
        listener.onSrtFailed(reason)
    }

    override fun onNewBitrate(bitrate: Long) {}

    override fun onDisconnect() {
        Log.w(TAG, "⚠️ SRT disconnected")
        isStreaming = false
        listener.onSrtDisconnected()
    }

    override fun onAuthError() {
        Log.e(TAG, "SRT auth error")
        listener.onSrtFailed("Auth Error")
    }

    override fun onAuthSuccess() {}
}
