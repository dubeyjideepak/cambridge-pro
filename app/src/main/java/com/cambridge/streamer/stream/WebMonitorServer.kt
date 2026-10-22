package com.cambridge.streamer.stream

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Ultra-Low Latency Direct Frame Web Server for OBS Browser Source.
 *
 * Transfers MJPEG frames directly from USB to Wi-Fi with ZERO re-encoding,
 * completely bypassing H.264 encoding and bypassing OBS Media Source's 2-second jitter buffer.
 *
 * Endpoints:
 * - http://<phone-ip>:8080/obs          -> Clean full-screen page for OBS Browser Source (<50ms delay)
 * - http://<phone-ip>:8080/stream.mjpeg -> Raw multipart MJPEG stream for OBS Media Source / VLC
 * - http://<phone-ip>:8080/             -> Live browser monitor for phones / laptops on Wi-Fi
 */
class WebMonitorServer(
    port: Int = 8080
) : NanoHTTPD(port) {

    @Volatile
    var isStreaming = false

    private val clientPipes = CopyOnWriteArrayList<PipedOutputStream>()
    @Volatile
    private var latestSnapshot: ByteArray? = null

    fun pushFrame(jpegBytes: ByteArray) {
        if (!isStreaming || jpegBytes.isEmpty()) return
        latestSnapshot = jpegBytes

        if (clientPipes.isEmpty()) return

        val boundary = "CAMBRIDGE_FRAME_BOUNDARY"
        val header = ("--$boundary\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: ${jpegBytes.size}\r\n\r\n").toByteArray()
        val footer = "\r\n".toByteArray()

        val deadPipes = mutableListOf<PipedOutputStream>()
        for (pipe in clientPipes) {
            try {
                pipe.write(header)
                pipe.write(jpegBytes)
                pipe.write(footer)
                pipe.flush()
            } catch (_: Exception) {
                deadPipes.add(pipe)
            }
        }
        if (deadPipes.isNotEmpty()) {
            clientPipes.removeAll(deadPipes)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri

        return when {
            uri == "/obs" || uri == "/obs.html" -> {
                val html = """
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                        <meta charset="utf-8">
                        <title>CamBridge Pro — OBS Zero-Lag Feed</title>
                        <style>
                            html, body {
                                margin: 0;
                                padding: 0;
                                width: 100%;
                                height: 100%;
                                overflow: hidden;
                                background-color: #000;
                            }
                            img {
                                width: 100vw;
                                height: 100vh;
                                object-fit: contain;
                                display: block;
                            }
                        </style>
                    </head>
                    <body>
                        <img src="/stream.mjpeg" alt="Live Feed" />
                    </body>
                    </html>
                """.trimIndent()
                newFixedLengthResponse(Response.Status.OK, "text/html", html)
            }
            uri == "/" || uri == "/index.html" -> {
                val host = session.headers["host"]?.split(":")?.get(0) ?: "phone-ip"
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
                                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
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
                                background: #00E676;
                                color: #000;
                                font-weight: bold;
                                padding: 4px 10px;
                                border-radius: 4px;
                                font-size: 13px;
                                letter-spacing: 1px;
                            }
                            .video-container {
                                flex: 1;
                                display: flex;
                                justify-content: center;
                                align-items: center;
                                background: #000;
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
                            .highlight { color: #00E676; font-family: monospace; font-weight: bold; }
                        </style>
                    </head>
                    <body>
                        <header>
                            <div style="font-size: 18px; font-weight: 600;">CamBridge Pro &bull; Zero-Lag Live Stream</div>
                            <div class="live-badge">⚡ ZERO-LAG DIRECT FEED</div>
                        </header>
                        <div class="video-container">
                            <img id="stream" src="/stream.mjpeg" alt="Live Camera Feed" />
                        </div>
                        <footer>
                            <div>Direct Hardware USB-to-Web Bridge &bull; 0ms Re-encode Delay</div>
                            <div>OBS Browser Source: <span class="highlight">http://$host:8080/obs</span></div>
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
                val bytes = latestSnapshot ?: ByteArray(0)
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
        val pipedIn = PipedInputStream(pipedOut, 512 * 1024)

        clientPipes.add(pipedOut)

        val response = newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=$boundary",
            pipedIn
        )
        response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate, pre-check=0, post-check=0, max-age=0")
        response.addHeader("Pragma", "no-cache")
        response.addHeader("Expires", "0")
        response.addHeader("Connection", "close")
        return response
    }

    override fun stop() {
        for (pipe in clientPipes) {
            try { pipe.close() } catch (_: Exception) {}
        }
        clientPipes.clear()
        super.stop()
    }
}
