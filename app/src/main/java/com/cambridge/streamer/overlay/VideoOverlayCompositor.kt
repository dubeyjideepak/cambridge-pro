package com.cambridge.streamer.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Handles real-time video fusion (OBS-like graphics overlay) directly on Android.
 * Renders:
 * 1. Katha Vachak Name Strap (Lower Third)
 * 2. Scrolling Text Ticker (Looping at bottom)
 * 3. Event Watermark / Logo
 * 4. Donation / UPI QR Code
 */
class VideoOverlayCompositor {

    // Configurable Overlay Data
    var isOverlayEnabled: Boolean = true
    var kathaTitle: String = "श्रीमद् भागवत कथा ज्ञान यज्ञ"
    var vyasJiName: String = "पूज्य व्यास जी महाराज"
    var dayText: String = "दिवस: 1 (कथा महात्म्य)"
    var tickerText: String = "।। श्री राधे कृष्ण ।। कथा समय: दोपहर 2:00 से सायं 6:00 बजे तक | आरती एवं प्रसाद प्रतिदिन सायं 6:00 बजे | संपर्क: 9876543210 ।।"

    // Prasang / Jhanki Image Overlay (Picture-in-Picture)
    var jhankiBitmap: Bitmap? = null
    var isJhankiVisible: Boolean = false
    var jhankiTitle: String = "लीला प्रसंग: बाल कृष्ण माखन चोरी"

    var logoBitmap: Bitmap? = null
    var upiQrBitmap: Bitmap? = null

    // Ticker animation
    private var tickerScrollX = 0f
    private val tickerSpeed = 3.5f

    // Paints
    private val strapBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E68B0000") // Deep maroon/saffron translucent background
        style = Paint.Style.FILL
    }

    private val strapBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD700") // Gold accent border
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val tickerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F011141A") // Dark contrast strip
        style = Paint.Style.FILL
    }

    private val tickerBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6C62828") // Red live tag on ticker
        style = Paint.Style.FILL
    }

    private val titleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFE082") // Soft gold
        textSize = 28f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val nameTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val dayTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80D8FF")
        textSize = 24f
    }

    private val tickerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    private val jhankiCardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D90D1117")
        style = Paint.Style.FILL
    }

    private val jhankiRibbonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E68B0000") // Maroon ribbon for caption
        style = Paint.Style.FILL
    }

    private val jhankiTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFF8E1")
        textSize = 22f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val jhankiBadgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    /**
     * Draws the broadcast graphics layers on top of the live video frame.
     */
    fun drawOverlays(canvas: Canvas, width: Int, height: Int) {
        if (!isOverlayEnabled) return

        val w = width.toFloat()
        val h = height.toFloat()

        // ── 1. Scrolling Ticker Strip with Docked Emblem ───────────────────────
        val tickerHeight = 52f
        val tickerTop = h - tickerHeight
        canvas.drawRect(0f, tickerTop, w, h, tickerBgPaint)
        canvas.drawLine(0f, tickerTop, w, tickerTop, strapBorderPaint)

        // Docked Left Emblem Badge ("🕉️ KATHA LIVE")
        val dockedBadgeWidth = 180f
        canvas.drawRect(0f, tickerTop, dockedBadgeWidth, h, tickerBadgePaint)
        canvas.drawLine(dockedBadgeWidth, tickerTop, dockedBadgeWidth, h, strapBorderPaint)
        canvas.drawText("🕉️ KATHA LIVE", 16f, h - 17f, jhankiBadgeTextPaint)

        // Animated scrolling text (clipped between docked badge and right edge)
        val textWidth = tickerTextPaint.measureText(tickerText)
        val textScrollAreaWidth = w - dockedBadgeWidth
        canvas.save()
        canvas.clipRect(dockedBadgeWidth + 10f, tickerTop, w, h)
        canvas.drawText(tickerText, (w - tickerScrollX), h - 16f, tickerTextPaint)
        canvas.restore()

        tickerScrollX += tickerSpeed
        if (tickerScrollX > w + textWidth) {
            tickerScrollX = 0f
        }

        // ── 2. Lower-Third Katha Vachak Name Strap ────────────────────────────
        val strapWidth = w * 0.44f
        val strapHeight = 110f
        val strapLeft = 40f
        val strapTop = tickerTop - strapHeight - 20f
        val strapRect = RectF(strapLeft, strapTop, strapLeft + strapWidth, strapTop + strapHeight)

        canvas.drawRoundRect(strapRect, 12f, 12f, strapBgPaint)
        canvas.drawRoundRect(strapRect, 12f, 12f, strapBorderPaint)

        // Text inside Lower Third
        canvas.drawText(kathaTitle, strapLeft + 20f, strapTop + 34f, titleTextPaint)
        canvas.drawText(vyasJiName, strapLeft + 20f, strapTop + 74f, nameTextPaint)
        canvas.drawText(dayText, strapLeft + 20f, strapTop + 102f, dayTextPaint)

        // ── 3. Prasang Jhanki Picture-in-Picture (Right Side Overlay) ─────────
        if (isJhankiVisible && jhankiBitmap != null) {
            val bmp = jhankiBitmap!!
            val jhankiSize = 250f
            val jhankiRight = w - 40f
            val jhankiLeft = jhankiRight - jhankiSize
            val jhankiTop = 40f
            val jhankiBottom = jhankiTop + jhankiSize
            val cardRect = RectF(jhankiLeft, jhankiTop, jhankiRight, jhankiBottom)

            // Card background & gold frame
            canvas.drawRoundRect(cardRect, 16f, 16f, jhankiCardBgPaint)

            // Draw image scaled inside
            canvas.save()
            val clipPath = android.graphics.Path().apply {
                addRoundRect(cardRect, 16f, 16f, android.graphics.Path.Direction.CW)
            }
            canvas.clipPath(clipPath)
            val srcRect = android.graphics.Rect(0, 0, bmp.width, bmp.height)
            val dstRect = RectF(jhankiLeft, jhankiTop, jhankiRight, jhankiBottom)
            canvas.drawBitmap(bmp, srcRect, dstRect, null)

            // Caption Ribbon at bottom of Jhanki Card
            val ribbonHeight = 44f
            val ribbonTop = jhankiBottom - ribbonHeight
            val ribbonRect = RectF(jhankiLeft, ribbonTop, jhankiRight, jhankiBottom)
            canvas.drawRect(ribbonRect, jhankiRibbonPaint)
            canvas.drawLine(jhankiLeft, ribbonTop, jhankiRight, ribbonTop, strapBorderPaint)

            // Center caption text
            val capWidth = jhankiTitlePaint.measureText(jhankiTitle)
            val capX = maxOf(jhankiLeft + 10f, jhankiLeft + (jhankiSize - capWidth) / 2f)
            canvas.drawText(jhankiTitle, capX, jhankiBottom - 14f, jhankiTitlePaint)

            // Top-left Live badge on Jhanki: "🔴 प्रसंग झांकी"
            val badgeRect = RectF(jhankiLeft + 8f, jhankiTop + 8f, jhankiLeft + 140f, jhankiTop + 36f)
            canvas.drawRoundRect(badgeRect, 6f, 6f, tickerBadgePaint)
            canvas.drawText("🔴 प्रसंग झांकी", jhankiLeft + 16f, jhankiTop + 28f, jhankiBadgeTextPaint)

            canvas.restore()

            // Outer gold border
            canvas.drawRoundRect(cardRect, 16f, 16f, strapBorderPaint)
        }

        // ── 4. Channel Logo / Watermark (Top Left) ────────────────────────────
        logoBitmap?.let { logo ->
            canvas.drawBitmap(logo, 40f, 40f, null)
        }

        // ── 5. Donation / UPI QR Code (Top Right, if Jhanki not covering) ──────
        if (!isJhankiVisible) {
            upiQrBitmap?.let { qr ->
                val qrLeft = w - qr.width - 40f
                canvas.drawBitmap(qr, qrLeft, 40f, null)
                canvas.drawText("सहयोग / दान हेतु QR", qrLeft - 10f, 40f + qr.height + 26f, dayTextPaint)
            }
        }
    }
}
