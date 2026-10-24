package com.cambridge.streamer.stream

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Ultra-Low Latency Direct Frame Web Server for OBS Browser Source.
 *
 * Uses a non-blocking Capacity-1 frame queue that guarantees ZERO lag accumulation.
 * Stale frames are instantly dropped if the client is slow, ensuring OBS always renders the live frame.
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
    var isStreaming = true

    var onClientCountChanged: ((Int) -> Unit)? = null

    @Volatile
    private var lastSnapshotRequestTime = 0L

    fun isClientStreamingActive(): Boolean {
        val hasMjpegClients = clientQueues.isNotEmpty()
        val hasRecentSnapshot = (System.currentTimeMillis() - lastSnapshotRequestTime) < 3500L
        return hasMjpegClients || hasRecentSnapshot
    }

    private fun notifyClientActivity() {
        onClientCountChanged?.invoke(if (isClientStreamingActive()) 1 else 0)
    }

    override fun createClientHandler(finalSocket: java.net.Socket, inputStream: java.io.InputStream): ClientHandler {
        try {
            finalSocket.tcpNoDelay = true
        } catch (_: Exception) {}
        return super.createClientHandler(finalSocket, inputStream)
    }

    private val clientQueues = CopyOnWriteArrayList<ArrayBlockingQueue<ByteArray>>()
    @Volatile
    private var latestSnapshot: ByteArray? = null

    private var placeholderJpeg: ByteArray? = null

    private fun getPlaceholderJpeg(): ByteArray {
        if (placeholderJpeg != null) return placeholderJpeg!!
        val bmp = android.graphics.Bitmap.createBitmap(640, 360, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(android.graphics.Color.parseColor("#06120E"))
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#00E676")
            textSize = 26f
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        canvas.drawText("⚡ CamBridge Pro — Waiting for HDMI 4K Signal...", 320f, 170f, paint)
        val subPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#80D8FF")
            textSize = 17f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        canvas.drawText("Connect Camera HDMI via USB-C Capture Card", 320f, 210f, subPaint)
        val stream = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, stream)
        val bytes = stream.toByteArray()
        placeholderJpeg = bytes
        return bytes
    }

    fun pushFrame(jpegBytes: ByteArray) {
        if (jpegBytes.isEmpty()) return
        latestSnapshot = jpegBytes

        if (clientQueues.isEmpty()) return

        for (queue in clientQueues) {
            // Drop unread previous frame if consumer hasn't consumed it yet (guarantees ZERO lag accumulation)
            queue.poll()
            queue.offer(jpegBytes)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri

        return when {
            uri == "/obs" || uri == "/obs.html" -> {
                notifyClientActivity()
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
                            #viewport {
                                width: 100vw;
                                height: 100vh;
                                object-fit: contain;
                                display: block;
                            }
                        </style>
                    </head>
                    <body>
                        <canvas id="viewport"></canvas>
                        <script>
                            const canvas = document.getElementById('viewport');
                            const ctx = canvas.getContext('2d', { alpha: false, desynchronized: true });
                            let pending = false;
                            let active = true;

                            function loadNextFrame() {
                                if (pending || !active) return;
                                pending = true;

                                const img = new Image();
                                img.onload = () => {
                                    requestAnimationFrame(() => {
                                        if (canvas.width !== img.naturalWidth || canvas.height !== img.naturalHeight) {
                                            canvas.width = img.naturalWidth;
                                            canvas.height = img.naturalHeight;
                                        }
                                        ctx.drawImage(img, 0, 0);
                                        pending = false;
                                        loadNextFrame();
                                    });
                                };
                                img.onerror = () => {
                                    pending = false;
                                    setTimeout(loadNextFrame, 40);
                                };
                                img.src = '/snapshot.jpg?t=' + performance.now();
                            }

                            loadNextFrame();
                        </script>
                    </body>
                    </html>
                """.trimIndent()
                val resp = newFixedLengthResponse(Response.Status.OK, "text/html", html)
                resp.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                resp
            }
            uri == "/obs-legacy" || uri == "/obs-mjpeg" -> {
                notifyClientActivity()
                val html = """
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                        <meta charset="utf-8">
                        <title>CamBridge Pro — OBS Feed (Legacy Fallback)</title>
                        <style>
                            html, body {
                                margin: 0;
                                padding: 0;
                                width: 100%;
                                height: 100%;
                                overflow: hidden;
                                background-color: #000;
                            }
                            #stream {
                                width: 100vw;
                                height: 100vh;
                                object-fit: contain;
                                display: block;
                            }
                        </style>
                    </head>
                    <body>
                        <img id="stream" src="/stream.mjpeg" alt="Live Feed" />
                    </body>
                    </html>
                """.trimIndent()
                val resp = newFixedLengthResponse(Response.Status.OK, "text/html", html)
                resp.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                resp
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
                                width: 100%;
                                height: 100%;
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
                        <script>
                            const img = document.getElementById('stream');
                            img.onerror = () => {
                                setTimeout(() => { img.src = '/stream.mjpeg?t=' + Date.now(); }, 1000);
                            };
                        </script>
                    </body>
                    </html>
                """.trimIndent()
                val resp = newFixedLengthResponse(Response.Status.OK, "text/html", html)
                resp.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                resp
            }
            uri == "/stream.mjpeg" -> {
                serveMjpegStream()
            }
            uri == "/snapshot.jpg" -> {
                lastSnapshotRequestTime = System.currentTimeMillis()
                notifyClientActivity()
                val bytes = latestSnapshot ?: getPlaceholderJpeg()
                val resp = newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong())
                resp.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                resp.addHeader("Pragma", "no-cache")
                resp.addHeader("Expires", "0")
                resp
            }
            else -> {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        }
    }

    private fun serveMjpegStream(): Response {
        val boundary = "CAMBRIDGE_FRAME_BOUNDARY"
        val queue = ArrayBlockingQueue<ByteArray>(1)

        // Seed with current latest frame so browser starts immediately
        val seed = latestSnapshot ?: getPlaceholderJpeg()
        queue.offer(seed)
        clientQueues.add(queue)
        notifyClientActivity()

        val stream = object : InputStream() {
            private var currentChunk: ByteArrayInputStream? = null
            @Volatile private var isClosed = false

            override fun read(): Int {
                val b = ByteArray(1)
                val read = read(b, 0, 1)
                return if (read > 0) b[0].toInt() and 0xFF else -1
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (isClosed) return -1

                while (currentChunk == null || currentChunk!!.available() == 0) {
                    if (isClosed) return -1
                    try {
                        val frameBytes = queue.poll(1000, TimeUnit.MILLISECONDS)
                            ?: (latestSnapshot ?: getPlaceholderJpeg())
                        val header = ("--$boundary\r\n" +
                                "Content-Type: image/jpeg\r\n" +
                                "Content-Length: ${frameBytes.size}\r\n\r\n").toByteArray()
                        val footer = "\r\n".toByteArray()

                        val combined = ByteArray(header.size + frameBytes.size + footer.size)
                        System.arraycopy(header, 0, combined, 0, header.size)
                        System.arraycopy(frameBytes, 0, combined, header.size, frameBytes.size)
                        System.arraycopy(footer, 0, combined, header.size + frameBytes.size, footer.size)
                        currentChunk = ByteArrayInputStream(combined)
                    } catch (_: InterruptedException) {
                        return -1
                    }
                }

                return currentChunk?.read(b, off, len) ?: -1
            }

            override fun close() {
                isClosed = true
                clientQueues.remove(queue)
                notifyClientActivity()
                super.close()
            }
        }

        val response = newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=$boundary",
            stream
        )
        response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate, pre-check=0, post-check=0, max-age=0")
        response.addHeader("Pragma", "no-cache")
        response.addHeader("Expires", "0")
        response.addHeader("Connection", "close")
        return response
    }

    override fun stop() {
        clientQueues.clear()
        super.stop()
    }
}
