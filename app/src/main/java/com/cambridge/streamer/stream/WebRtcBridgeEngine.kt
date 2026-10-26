package com.cambridge.streamer.stream

import android.content.Context
import android.util.Log
import android.view.Surface
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WebRTC Bridge Engine — Zero-Freeze, Zero-Lag Camera Bridge over UDP.
 *
 * Transport: SRTP over UDP (replaces HTTP-FLV over TCP)
 *
 * Why this eliminates freezing:
 *   TCP (old): A single lost packet blocks everything. OBS waits 200-400ms for retransmit.
 *   UDP (WebRTC): A lost packet is skipped. Video keeps flowing. One bad frame = 16ms blur.
 *
 * Pipeline:
 *   UVC Camera → [inputSurface] → SurfaceTextureHelper → WebRTC VideoTrack
 *                                                              ↓
 *                                               DTLS-SRTP over UDP/Wi-Fi
 *                                                              ↓
 *                                           OBS Browser Source (http://phone-ip:8080/webrtc)
 */
class WebRtcBridgeEngine(private val context: Context) {

    /** Implement to send WebRTC signaling JSON to the connected browser client */
    fun interface SignalingCallback {
        fun sendToClient(json: String)
    }

    private val TAG = "WebRtcBridgeEngine"

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "WebRtcEngineThread").also { it.isDaemon = true }
    }

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var peerConnection: PeerConnection? = null

    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    @Volatile private var isRemoteDescriptionSet = false

    /** Set by WebRtcSignalingServer — sends SDP offer + ICE candidates to browser */
    @Volatile var signalingCallback: SignalingCallback? = null

    /**
     * The Surface backed by WebRTC's SurfaceTexture.
     * Pass this to nativeUvcEngine.addSurface() — frames flow UVC → WebRTC automatically.
     */
    @Volatile var inputSurface: Surface? = null
        private set

    @Volatile var isReady = false
        private set

    fun setTextureSize(width: Int, height: Int) {
        executor.execute {
            try {
                surfaceHelper?.setTextureSize(width, height)
                surfaceHelper?.surfaceTexture?.setDefaultBufferSize(width, height)
                Log.d(TAG, "WebRTC surface texture size updated: ${width}x${height}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update texture size", e)
            }
        }
    }

    // ─── Initialization ────────────────────────────────────────────────────────

    fun init(onReady: (() -> Unit)? = null) {
        executor.execute {
            try {
                // Step 1: Create shared EGL context (GPU memory bridge)
                eglBase = EglBase.create()

                // Step 2: Initialize WebRTC native libraries (once per process)
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions
                        .builder(context.applicationContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )

                // Step 3: Build factory with hardware GPU encoder + software fallback (VP8, VP9, H264)
                factory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(
                        DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true)
                    )
                    .setVideoDecoderFactory(
                        DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)
                    )
                    .createPeerConnectionFactory()

                // Step 4: Create SurfaceTextureHelper — bridges GPU surface frames into WebRTC
                surfaceHelper = SurfaceTextureHelper.create(
                    "WebRtcCaptureSurface",
                    eglBase!!.eglBaseContext
                ).also {
                    it.setTextureSize(1920, 1080)
                }

                // Step 5: Create VideoSource — WebRTC reads frames from the surface texture
                videoSource = factory!!.createVideoSource(/* isScreencast= */ false).also { src ->
                    src.capturerObserver.onCapturerStarted(true)
                    surfaceHelper!!.startListening { frame ->
                        src.capturerObserver.onFrameCaptured(frame)
                    }
                }

                // Step 6: Create the actual Android Surface backed by the SurfaceTexture.
                //         This is what we hand to libUVCCamera → UVC frames flow directly here.
                val st = surfaceHelper!!.surfaceTexture.also {
                    it.setDefaultBufferSize(1920, 1080)
                }
                inputSurface = Surface(st)

                // Step 7: Wrap as a VideoTrack (sent to the browser via PeerConnection)
                videoTrack = factory!!.createVideoTrack("CAMv0", videoSource).also {
                    it.setEnabled(true)
                }

                isReady = true
                Log.d(TAG, "✅ WebRTC engine ready. inputSurface=${inputSurface.hashCode()}")
                android.os.Handler(android.os.Looper.getMainLooper()).post { onReady?.invoke() }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to initialize WebRTC engine", e)
            }
        }
    }

    // ─── Peer Connection ───────────────────────────────────────────────────────

    /**
     * Creates a new PeerConnection and sends an SDP offer to the browser.
     * Called when a browser opens the /webrtc page and WebSocket connects.
     */
    fun startPeerSession() {
        executor.execute {
            try {
                // Close any previous session cleanly
                peerConnection?.close()
                peerConnection = null
                isRemoteDescriptionSet = false
                pendingIceCandidates.clear()

                // STUN server provides reflexive ICE fallback for zero-freeze traversal
                val iceServers = listOf(
                    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
                )
                val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                    iceTransportsType = PeerConnection.IceTransportsType.ALL
                }

                val pcObserver = object : PeerConnection.Observer {
                    override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
                    override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                        Log.d(TAG, "ICE state: $s")
                    }
                    override fun onIceConnectionReceivingChange(b: Boolean) {}
                    override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}

                    // Each ICE candidate is a possible local IP:port for UDP media.
                    // We send them to the browser so it can pick the best one.
                    override fun onIceCandidate(c: IceCandidate?) {
                        c ?: return
                        val json = JSONObject().apply {
                            put("type", "ice")
                            put("candidate", c.sdp)
                            put("sdpMid", c.sdpMid)
                            put("sdpMLineIndex", c.sdpMLineIndex)
                        }.toString()
                        signalingCallback?.sendToClient(json)
                    }

                    override fun onIceCandidatesRemoved(p: Array<out IceCandidate>?) {}
                    override fun onAddStream(p: MediaStream?) {}
                    override fun onRemoveStream(p: MediaStream?) {}
                    override fun onDataChannel(p: DataChannel?) {}
                    override fun onRenegotiationNeeded() {}
                    override fun onAddTrack(p: RtpReceiver?, s: Array<out MediaStream>?) {}
                }

                val pc = factory!!.createPeerConnection(rtcConfig, pcObserver)
                    ?: run { Log.e(TAG, "Failed to create PeerConnection"); return@execute }

                // Add our camera video track. Browser will receive this as a MediaStream.
                pc.addTrack(videoTrack!!, listOf("CamBridgeProStream"))
                peerConnection = pc

                // Generate SDP offer (describes our video capabilities to the browser)
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                }

                val latch = CountDownLatch(1)
                pc.createOffer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription?) {
                        sdp ?: run { latch.countDown(); return }
                        pc.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(p: SessionDescription?) {}
                            override fun onSetSuccess() {
                                val json = JSONObject().apply {
                                    put("type", "offer")
                                    put("sdp", sdp.description)
                                }.toString()
                                signalingCallback?.sendToClient(json)
                                Log.d(TAG, "SDP offer sent to browser")
                                latch.countDown()
                            }
                            override fun onCreateFailure(e: String?) { Log.e(TAG, "SDP local desc fail: $e"); latch.countDown() }
                            override fun onSetFailure(e: String?) { Log.e(TAG, "SDP set local fail: $e"); latch.countDown() }
                        }, sdp)
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(e: String?) { Log.e(TAG, "Offer create fail: $e"); latch.countDown() }
                    override fun onSetFailure(e: String?) { latch.countDown() }
                }, constraints)

                latch.await(5, TimeUnit.SECONDS)

            } catch (e: Exception) {
                Log.e(TAG, "Error in startPeerSession", e)
            }
        }
    }

    /** Called when the browser sends back its SDP answer via WebSocket */
    fun handleAnswerFromClient(sdp: String) {
        executor.execute {
            try {
                val desc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
                peerConnection?.setRemoteDescription(object : SdpObserver {
                    override fun onCreateSuccess(p: SessionDescription?) {}
                    override fun onSetSuccess() {
                        Log.d(TAG, "✅ Remote SDP answer applied — UDP media should flow now")
                        isRemoteDescriptionSet = true
                        for (c in pendingIceCandidates) {
                            peerConnection?.addIceCandidate(c)
                        }
                        pendingIceCandidates.clear()
                    }
                    override fun onCreateFailure(e: String?) { Log.e(TAG, "Answer create fail: $e") }
                    override fun onSetFailure(e: String?) { Log.e(TAG, "Answer set fail: $e") }
                }, desc)
            } catch (e: Exception) {
                Log.e(TAG, "handleAnswerFromClient error", e)
            }
        }
    }

    /** Called when the browser sends an ICE candidate via WebSocket */
    fun handleIceCandidateFromClient(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        executor.execute {
            try {
                val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
                if (isRemoteDescriptionSet) {
                    peerConnection?.addIceCandidate(iceCandidate)
                } else {
                    pendingIceCandidates.add(iceCandidate)
                }
            } catch (e: Exception) {
                Log.e(TAG, "handleIceCandidateFromClient error", e)
            }
        }
    }

    // ─── Cleanup ───────────────────────────────────────────────────────────────

    fun release() {
        isReady = false
        signalingCallback = null
        executor.execute {
            peerConnection?.close()
            peerConnection = null
            videoTrack?.dispose()
            videoTrack = null
            videoSource?.dispose()
            videoSource = null
            surfaceHelper?.dispose()
            surfaceHelper = null
            inputSurface?.release()
            inputSurface = null
            factory?.dispose()
            factory = null
            eglBase?.release()
            eglBase = null
        }
        executor.shutdown()
    }
}
