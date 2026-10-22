package com.cambridge.streamer.stream

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

class WebMonitorServer(
    port: Int = 8080,
    private val onSnapshotRequested: () -> ByteArray?
) : NanoHTTPD(port) {

    @Volatile
    var isStreaming = false

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri

        return when {
            uri == "/" || uri == "/index.html" -> {
                val html = """
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                        <meta charset="UTF-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <title>CamBridge Pro - Live Monitor</title>
                        <style>
                            body {
                                margin: 0;
                                background-color: #0b0d10;
                                color: #fff;
                                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                                display: flex;
                                flex-direction: column;
                                height: 100vh;
                                overflow: hidden;
                            }
                            header {
                                background: #161920;
                                padding: 12px 24px;
                                display: flex;
                                align-items: center;
                                justify-content: space-between;
                                border-bottom: 1px solid #282c37;
                            }
                            .live-badge {
                                background: #e53935;
                                color: #fff;
                                font-weight: bold;
                                padding: 4px 10px;
                                border-radius: 4px;
                                font-size: 13px;
                                letter-spacing: 1px;
                                display: inline-flex;
                                align-items: center;
                                gap: 6px;
                            }
                            .live-dot {
                                width: 8px;
                                height: 8px;
                                background: #fff;
                                border-radius: 50%;
                                animation: blink 1s infinite alternate;
                            }
                            @keyframes blink {
                                from { opacity: 1; }
                                to { opacity: 0.2; }
                            }
                            .video-container {
                                flex: 1;
                                display: flex;
                                justify-content: center;
                                align-items: center;
                                background: #000;
                                position: relative;
                            }
                            .video-container img {
                                max-width: 100%;
                                max-height: 100%;
                                object-fit: contain;
                            }
                            footer {
                                background: #161920;
                                padding: 10px 24px;
                                font-size: 14px;
                                color: #8e94a0;
                                display: flex;
                                justify-content: space-between;
                            }
                            .obs-tip {
                                color: #64b5f6;
                                font-family: monospace;
                            }
                        </style>
                    </head>
                    <body>
                        <header>
                            <div style="font-size: 18px; font-weight: 600;">CamBridge Pro &bull; Live Katha &amp; Event Stream</div>
                            <div class="live-badge"><span class="live-dot"></span>LIVE ON-AIR</div>
                        </header>
                        <div class="video-container">
                            <img id="stream" src="/stream.mjpeg" alt="Live Camera Feed" />
                        </div>
                        <footer>
                            <div>Resolution: 1080p Full HD &bull; Low-Latency Wi-Fi Link</div>
                            <div>OBS Source: <span class="obs-tip">rtsp://${session.headers["host"]?.split(":")?.get(0) ?: "phone-ip"}:8554/live</span></div>
                        </footer>
                    </body>
                    </html>
                """.trimIndent()
                newFixedLengthResponse(Response.Status.OK, "text/html", html)
            }
            uri == "/stream.mjpeg" -> {
                serveMjpegStream()
            }
            uri == "/snapshot.jpg" -> {
                val bytes = onSnapshotRequested() ?: ByteArray(0)
                newChunkedResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes))
            }
            else -> {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        }
    }

    private fun serveMjpegStream(): Response {
        val boundary = "CAMBRIDGE_FRAME_BOUNDARY"
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut)

        Thread {
            try {
                while (isStreaming) {
                    val frameBytes = onSnapshotRequested()
                    if (frameBytes != null && frameBytes.isNotEmpty()) {
                        val header = ("--$boundary\r\n" +
                                "Content-Type: image/jpeg\r\n" +
                                "Content-Length: ${frameBytes.size}\r\n\r\n").toByteArray()
                        pipedOut.write(header)
                        pipedOut.write(frameBytes)
                        pipedOut.write("\r\n".toByteArray())
                        pipedOut.flush()
                    }
                    Thread.sleep(33) // ~30 FPS
                }
            } catch (_: Exception) {
            } finally {
                try { pipedOut.close() } catch (_: Exception) {}
            }
        }.start()

        val response = newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=$boundary",
            pipedIn
        )
        response.addHeader("Cache-Control", "no-cache, private")
        response.addHeader("Pragma", "no-cache")
        return response
    }
}
