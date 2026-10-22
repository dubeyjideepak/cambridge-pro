package com.cambridge.streamer.overlay

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View

/**
 * Transparent HUD overlay placed directly on top of the Camera Viewfinder.
 * Renders the lower-third, scrolling ticker, and logo in real-time.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    val compositor = VideoOverlayCompositor()

    init {
        // Continuous animation loop for the scrolling ticker
        post(object : Runnable {
            override fun run() {
                if (compositor.isOverlayEnabled) {
                    invalidate()
                }
                postDelayed(this, 16) // ~60 FPS
            }
        })
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        compositor.drawOverlays(canvas, width, height)
    }
}
