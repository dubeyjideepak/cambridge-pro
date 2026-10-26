package com.cambridge.streamer.stream

import android.util.Log
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocketFrame.CloseCode
import org.json.JSONObject
import java.io.IOException

/**
 * WebRTC Signaling Server — WebSocket on port 8081.
 *
 * Purpose: Exchange SDP offer/answer and ICE candidates between the Android phone
 * and the OBS Browser Source (or any browser) so that the WebRTC UDP media channel
 * can be established.
 *
 * Signaling protocol (JSON over WebSocket):
 *   Phone → Browser:  {"type":"offer","sdp":"..."}
 *   Phone → Browser:  {"type":"ice","candidate":"...","sdpMid":"0","sdpMLineIndex":0}
 *   Browser → Phone:  {"type":"answer","sdp":"..."}
 *   Browser → Phone:  {"type":"ice","candidate":"...","sdpMid":"0","sdpMLineIndex":0}
 *
 * After signaling completes, SRTP media flows directly over UDP — no more data
 * passes through this server. This is exactly how VDO.Ninja works.
 */
class WebRtcSignalingServer(
    port: Int = 8081,
    private val engine: WebRtcBridgeEngine
) : NanoWSD(port) {

    private val TAG = "WebRtcSignaling"

    @Volatile
    private var activeSocket: NanoWSD.WebSocket? = null

    /** Called by WebRtcBridgeEngine to push SDP/ICE to the connected browser */
    fun sendToClient(json: String) {
        try {
            activeSocket?.send(json)
        } catch (e: IOException) {
            Log.w(TAG, "Send to client failed: ${e.message}")
        }
    }

    override fun openWebSocket(handshake: IHTTPSession): NanoWSD.WebSocket {
        return object : NanoWSD.WebSocket(handshake) {

            override fun onOpen() {
                Log.d(TAG, "⚡ WebRTC browser client connected — starting peer session")
                activeSocket = this

                // Wire engine → socket
                engine.signalingCallback = WebRtcBridgeEngine.SignalingCallback { json ->
                    try { send(json) } catch (_: IOException) {}
                }

                // Trigger SDP offer generation
                engine.startPeerSession()
            }

            override fun onMessage(message: NanoWSD.WebSocketFrame) {
                try {
                    val payload = message.textPayload ?: return
                    val msg = JSONObject(payload)
                    when (msg.optString("type")) {
                        "ping" -> {
                            try {
                                send(JSONObject().put("type", "pong").toString())
                            } catch (_: Exception) {}
                        }
                        "answer" -> {
                            val sdp = msg.getString("sdp")
                            Log.d(TAG, "Received SDP answer from browser")
                            engine.handleAnswerFromClient(sdp)
                        }
                        "ice" -> {
                            val candidate = msg.getString("candidate")
                            val sdpMid = msg.optString("sdpMid", "0")
                            val sdpMLineIndex = msg.optInt("sdpMLineIndex", 0)
                            engine.handleIceCandidateFromClient(candidate, sdpMid, sdpMLineIndex)
                        }
                        else -> Log.w(TAG, "Unknown signaling message type: ${msg.optString("type")}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing signaling message: ${e.message}")
                }
            }

            override fun onClose(
                code: CloseCode?,
                reason: String?,
                initiatedByRemote: Boolean
            ) {
                Log.d(TAG, "WebSocket closed: $reason (code=$code remote=$initiatedByRemote)")
                if (activeSocket == this) {
                    activeSocket = null
                    engine.signalingCallback = null
                }
            }

            override fun onPong(pong: NanoWSD.WebSocketFrame?) {}

            override fun onException(exception: IOException?) {
                Log.w(TAG, "WebSocket exception: ${exception?.message}")
                if (activeSocket == this) {
                    activeSocket = null
                    engine.signalingCallback = null
                }
            }
        }
    }
}
