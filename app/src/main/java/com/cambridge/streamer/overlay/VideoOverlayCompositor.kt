package com.cambridge.streamer.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Real-time Video Fusion Engine (Broadcast-grade Graphics Compositor)
 *
 * Designed specifically for religious Katha prasaran, live discourses, and events:
 * 1. Event & Speaker Lower-Third Nameplate
 * 2. Rotating Bottom Rolling Ticker (Customizable messages, auto-advances on loop)
 * 3. Dynamic UPI QR Code (Auto-generated from VPA or loaded from image, timed display)
 * 4. Micro-graphics / Jhanki PiP (1-tap operator cue with custom captions)
 * 5. Event Logo / Watermark
 */
class VideoOverlayCompositor {

    // Global toggle
    var isOverlayEnabled: Boolean = true

    // ── 1. Nameplate Lower-Third ───────────────────────────────────────────────
    var isNameplateVisible: Boolean = true
    var kathaTitle: String = "श्रीमद् भागवत कथा ज्ञान यज्ञ"
    var vyasJiName: String = "पूज्य व्यास जी महाराज"
    var dayText: String = "दिवस: 1 (कथा महात्म्य एवं मंगलाचरण)"

    // ── 2. Dynamic Rotating Rolling Ticker ─────────────────────────────────────
    var isTickerVisible: Boolean = true
    private val tickerMessages = mutableListOf<String>()
    private var currentTickerIndex = 0
    private var tickerScrollX = 0f
    private val tickerSpeed = 3.5f

    // ── 3. Dynamic UPI QR Code ─────────────────────────────────────────────────
    var isQrVisible: Boolean = false
    var upiQrBitmap: Bitmap? = null
    var upiQrSubtitle: String = "सहयोग / सेवा हेतु QR कोड"

    // ── 4. Micro-graphics / Jhanki PiP (Human Cue) ─────────────────────────────
    var isJhankiVisible: Boolean = false
    var jhankiBitmap: Bitmap? = null
    var jhankiTitle: String = "लीला प्रसंग: बाल कृष्ण माखन चोरी"

    // ── 5. Channel Watermark / Logo ────────────────────────────────────────────
    var logoBitmap: Bitmap? = null

    // ── Paints ─────────────────────────────────────────────────────────────────
    private val strapBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E612261E") // Luxury deep emerald background
        style = Paint.Style.FILL
    }

    private val strapBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD700") // Divine gold accent border
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val tickerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F506120E") // Midnight obsidian emerald
        style = Paint.Style.FILL
    }

    private val tickerBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6059669") // Emerald Live tag
        style = Paint.Style.FILL
    }

    private val titleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FDE68A") // Soft gold
        textSize = 28f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val nameTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val dayTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A7F3D0") // Mint emerald
        textSize = 24f
    }

    private val tickerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    private val jhankiCardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E60A1813")
        style = Paint.Style.FILL
    }

    private val jhankiRibbonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6059669")
        style = Paint.Style.FILL
    }

    private val jhankiTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFF8E1")
        textSize = 22f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val qrBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    init {
        // Default initial rotation messages
        tickerMessages.add("।। श्री राधे कृष्ण ।। कथा समय: दोपहर 2:00 से सायं 6:00 बजे तक")
        tickerMessages.add("🌸 दिव्य महाआरती एवं महाप्रसाद प्रतिदिन सायं 6:00 बजे | सपरिवार सादर आमंत्रित हैं")
        tickerMessages.add("📞 कथा आयोजन एवं लाइव प्रसारण सेवा हेतु संपर्क: 9876543210")
    }

    // ── Public Helpers ─────────────────────────────────────────────────────────

    /**
     * Set custom ticker messages. Can be a single long string or multiple messages
     * separated by newline (\n) or pipe (|).
     */
    fun setCustomTicker(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return

        val items = when {
            trimmed.contains("\n") -> trimmed.split("\n")
            trimmed.contains("|")  -> trimmed.split("|")
            else                   -> listOf(trimmed)
        }.map { it.trim() }.filter { it.isNotEmpty() }

        synchronized(tickerMessages) {
            tickerMessages.clear()
            tickerMessages.addAll(items)
            currentTickerIndex = 0
            tickerScrollX = 0f
        }
    }

    /**
     * Generate dynamic UPI QR Code on the device from VPA string.
     */
    fun generateUpiQr(upiId: String, payeeName: String = "Katha Sewa", sizePx: Int = 220) {
        try {
            val uri = "upi://pay?pa=$upiId&pn=${java.net.URLEncoder.encode(payeeName, "UTF-8")}&cu=INR"
            val bitMatrix = QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, sizePx, sizePx)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            upiQrBitmap = bmp
            isQrVisible = true
        } catch (e: Exception) {
            android.util.Log.e("VideoOverlayCompositor", "Failed to generate UPI QR", e)
        }
    }

    /**
     * Main Canvas Drawing Routine — Renders layers over live video.
     */
    fun drawOverlays(canvas: Canvas, width: Int, height: Int) {
        if (!isOverlayEnabled) return

        val w = width.toFloat()
        val h = height.toFloat()
        val scale = (h / 720f).coerceIn(0.6f, 1.5f)

        // ── 1. Scrolling Ticker Strip with Docked Emblem ───────────────────────
        if (isTickerVisible) {
            val tickerHeight = 52f * scale
            val tickerTop = h - tickerHeight
            canvas.drawRect(0f, tickerTop, w, h, tickerBgPaint)
            canvas.drawLine(0f, tickerTop, w, tickerTop, strapBorderPaint)

            // Docked Left Emblem Badge ("🕉️ KATHA LIVE")
            val dockedBadgeWidth = 170f * scale
            canvas.drawRect(0f, tickerTop, dockedBadgeWidth, h, tickerBadgePaint)
            canvas.drawLine(dockedBadgeWidth, tickerTop, dockedBadgeWidth, h, strapBorderPaint)

            badgeTextPaint.textSize = 17f * scale
            canvas.drawText("🕉️ KATHA LIVE", 16f * scale, h - 16f * scale, badgeTextPaint)

            // Current message in the schedule rotation
            val currentMessage = synchronized(tickerMessages) {
                if (tickerMessages.isNotEmpty()) tickerMessages[currentTickerIndex % tickerMessages.size]
                else "।। श्री राधे कृष्ण ।।"
            }

            tickerTextPaint.textSize = 23f * scale
            val textWidth = tickerTextPaint.measureText(currentMessage)

            canvas.save()
            canvas.clipRect(dockedBadgeWidth + 10f * scale, tickerTop, w, h)
            canvas.drawText(currentMessage, (w - tickerScrollX), h - 15f * scale, tickerTextPaint)
            canvas.restore()

            tickerScrollX += (tickerSpeed * scale)
            if (tickerScrollX > w + textWidth) {
                tickerScrollX = 0f
                synchronized(tickerMessages) {
                    if (tickerMessages.isNotEmpty()) {
                        currentTickerIndex = (currentTickerIndex + 1) % tickerMessages.size
                    }
                }
            }
        }

        // ── 2. Lower-Third Katha Vachak Name Strap ────────────────────────────
        if (isNameplateVisible) {
            val tickerHeight = if (isTickerVisible) 52f * scale else 0f
            val strapWidth = (w * 0.44f).coerceAtLeast(320f * scale)
            val strapHeight = 104f * scale
            val strapLeft = 32f * scale
            val strapTop = h - tickerHeight - strapHeight - 16f * scale
            val strapRect = RectF(strapLeft, strapTop, strapLeft + strapWidth, strapTop + strapHeight)

            canvas.drawRoundRect(strapRect, 12f * scale, 12f * scale, strapBgPaint)
            canvas.drawRoundRect(strapRect, 12f * scale, 12f * scale, strapBorderPaint)

            titleTextPaint.textSize = 24f * scale
            nameTextPaint.textSize = 32f * scale
            dayTextPaint.textSize = 20f * scale

            canvas.drawText(kathaTitle, strapLeft + 18f * scale, strapTop + 30f * scale, titleTextPaint)
            canvas.drawText(vyasJiName, strapLeft + 18f * scale, strapTop + 68f * scale, nameTextPaint)
            canvas.drawText(dayText, strapLeft + 18f * scale, strapTop + 94f * scale, dayTextPaint)
        }

        // ── 3. Prasang Jhanki Picture-in-Picture (Right Side Overlay) ─────────
        if (isJhankiVisible && jhankiBitmap != null) {
            val bmp = jhankiBitmap!!
            val jhankiSize = 240f * scale
            val jhankiRight = w - 32f * scale
            val jhankiLeft = jhankiRight - jhankiSize
            val jhankiTop = 32f * scale
            val jhankiBottom = jhankiTop + jhankiSize
            val cardRect = RectF(jhankiLeft, jhankiTop, jhankiRight, jhankiBottom)

            // Card background & gold frame
            canvas.drawRoundRect(cardRect, 14f * scale, 14f * scale, jhankiCardBgPaint)

            canvas.save()
            val clipPath = android.graphics.Path().apply {
                addRoundRect(cardRect, 14f * scale, 14f * scale, android.graphics.Path.Direction.CW)
            }
            canvas.clipPath(clipPath)

            val srcRect = Rect(0, 0, bmp.width, bmp.height)
            val dstRect = RectF(jhankiLeft, jhankiTop, jhankiRight, jhankiBottom)
            canvas.drawBitmap(bmp, srcRect, dstRect, null)

            // Caption Ribbon at bottom of Jhanki Card
            val ribbonHeight = 40f * scale
            val ribbonTop = jhankiBottom - ribbonHeight
            val ribbonRect = RectF(jhankiLeft, ribbonTop, jhankiRight, jhankiBottom)
            canvas.drawRect(ribbonRect, jhankiRibbonPaint)
            canvas.drawLine(jhankiLeft, ribbonTop, jhankiRight, ribbonTop, strapBorderPaint)

            jhankiTitlePaint.textSize = 20f * scale
            val capWidth = jhankiTitlePaint.measureText(jhankiTitle)
            val capX = maxOf(jhankiLeft + 8f * scale, jhankiLeft + (jhankiSize - capWidth) / 2f)
            canvas.drawText(jhankiTitle, capX, jhankiBottom - 13f * scale, jhankiTitlePaint)

            // Top-left Live badge on Jhanki: "🔴 प्रसंग झांकी"
            val badgeRect = RectF(jhankiLeft + 8f * scale, jhankiTop + 8f * scale, jhankiLeft + 130f * scale, jhankiTop + 32f * scale)
            canvas.drawRoundRect(badgeRect, 6f * scale, 6f * scale, tickerBadgePaint)
            badgeTextPaint.textSize = 15f * scale
            canvas.drawText("🔴 प्रसंग झांकी", jhankiLeft + 14f * scale, jhankiTop + 25f * scale, badgeTextPaint)

            canvas.restore()

            // Outer gold border
            canvas.drawRoundRect(cardRect, 14f * scale, 14f * scale, strapBorderPaint)
        }

        // ── 4. Channel Logo / Watermark (Top Left) ────────────────────────────
        logoBitmap?.let { logo ->
            canvas.drawBitmap(logo, 32f * scale, 32f * scale, null)
        }

        // ── 5. Donation / UPI QR Code (Top Right, if Jhanki not showing) ──────
        if (isQrVisible && !isJhankiVisible) {
            upiQrBitmap?.let { qr ->
                val qrSize = 160f * scale
                val qrRight = w - 32f * scale
                val qrLeft = qrRight - qrSize
                val qrTop = 32f * scale
                val qrBottom = qrTop + qrSize
                val qrRect = RectF(qrLeft, qrTop, qrRight, qrBottom)

                // White backdrop for scanner reliability
                canvas.drawRoundRect(RectF(qrLeft - 6f, qrTop - 6f, qrRight + 6f, qrBottom + 6f), 10f * scale, 10f * scale, qrBgPaint)
                canvas.drawBitmap(qr, null, qrRect, null)
                canvas.drawRoundRect(RectF(qrLeft - 6f, qrTop - 6f, qrRight + 6f, qrBottom + 6f), 10f * scale, 10f * scale, strapBorderPaint)

                // Subtitle under QR
                dayTextPaint.textSize = 18f * scale
                val textW = dayTextPaint.measureText(upiQrSubtitle)
                val subX = qrLeft + (qrSize - textW) / 2f
                canvas.drawText(upiQrSubtitle, subX, qrBottom + 26f * scale, dayTextPaint)
            }
        }
    }
}
