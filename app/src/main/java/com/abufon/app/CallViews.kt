package com.abufon.app

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View

/**
 * The two rings around granny's photo that move with her voice while there is no video (v4
 * "incallNoVideo"). bg_voice_ring and bg_voice_ring_outer are laid on the photo frame (the view
 * minus [inset] on every side) and scaled about its centre: inner 1.02-1.07, outer 1.06-1.13.
 * [level] (0..1) is the latest loudness; the drawn level follows it smoothed over ~120 ms.
 * With animations off the rings are shown still, at their resting size.
 */
class VoiceRingsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs), Choreographer.FrameCallback {
    private val inset: Int
    private val inner = context.getDrawable(R.drawable.bg_voice_ring)!!
    private val outer = context.getDrawable(R.drawable.bg_voice_ring_outer)!!
    private var running = false
    private var lastFrame = 0L
    private var drawn = 0f

    /** Latest loudness of granny's voice, 0..1. */
    var level = 0f
        set(value) { field = value.coerceIn(0f, 1f) }

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.VoiceRingsView)
        inset = a.getDimensionPixelSize(R.styleable.VoiceRingsView_ringInset, 0)
        a.recycle()
    }

    /** Start following [level]. Does nothing (rings stay still) when animations are off. */
    fun start() {
        if (running || !motionEnabled) { if (!motionEnabled) rest(); return }
        running = true
        lastFrame = SystemClock.uptimeMillis()
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        rest()
    }

    private fun rest() {
        level = 0f
        if (drawn != 0f) { drawn = 0f; invalidate() }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val now = SystemClock.uptimeMillis()
        val next = VoiceLevel.smooth(drawn, level, (now - lastFrame).toFloat())
        lastFrame = now
        if (kotlin.math.abs(next - drawn) > 0.002f) { drawn = next; invalidate() }
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        inner.setBounds(inset, inset, w - inset, h - inset)
        outer.setBounds(inset, inset, w - inset, h - inset)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        for ((ring, scale) in arrayOf(outer to VoiceLevel.outerScale(drawn), inner to VoiceLevel.innerScale(drawn))) {
            val save = canvas.save()
            canvas.scale(scale, scale, cx, cy)
            ring.draw(canvas)
            canvas.restoreToCount(save)
        }
    }
}
