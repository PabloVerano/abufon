package com.abufon.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.widget.FrameLayout

/**
 * Runs [onFrame] with the elapsed milliseconds on every display frame for [durationMs], then
 * [onEnd]. Unlike ValueAnimator it keeps real time when the system animator scale is 0, so it is
 * used where the timing is behaviour (the corner hold, the tap reaction, the wake-up cross-fade).
 */
class Timeline(
    private val durationMs: Long,
    private val onFrame: (Long) -> Unit,
    private val onEnd: () -> Unit = {},
) : Choreographer.FrameCallback {
    private var startedAt = 0L
    var running = false
        private set

    fun start(): Timeline {
        running = true
        startedAt = SystemClock.uptimeMillis()
        onFrame(0)
        Choreographer.getInstance().postFrameCallback(this)
        return this
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val t = SystemClock.uptimeMillis() - startedAt
        if (t >= durationMs) {
            running = false
            onFrame(durationMs)
            onEnd()
        } else {
            onFrame(t)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Stops without calling onEnd. */
    fun cancel() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }
}

/**
 * The navy plaque that takes the call button's place while resting or paused (v4). Same shape as
 * the call button: a face with a 3D shadow [shadowDepth] tall under it; its child (the icon) is
 * centred on the face. It is drawn here rather than with btn_rest_plaque because the designer's
 * tap reaction (JSON "tapReaction" and portrait_resting_tapped.png) presses the face 8 px, darkens
 * it to gp_rest_plaque_pressed and shows a soft round highlight, none of which the drawable's
 * pressed item (12 px, same colour) gives. The press is not tied to touch: the home screen sets
 * [press] during the reaction, so ignored taps show nothing.
 */
class RestPlaqueView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val shadowDepth: Float
    private val pressShift: Float
    private val radius: Float
    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.gp_rest_plaque_shadow) }
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.gp_white) }
    private val up = context.getColor(R.color.gp_rest_plaque)
    private val down = context.getColor(R.color.gp_rest_plaque_pressed)

    /** 0 = at rest, 1 = pressed down by pressShift. */
    var press = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            for (i in 0 until childCount) getChildAt(i).translationY = pressShift * field
            invalidate()
        }

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.RestPlaqueView)
        shadowDepth = a.getDimension(R.styleable.RestPlaqueView_shadowDepth, 0f)
        pressShift = a.getDimension(R.styleable.RestPlaqueView_pressShift, 0f)
        radius = a.getDimension(R.styleable.RestPlaqueView_cornerRadius, 0f)
        a.recycle()
        setWillNotDraw(false)
        setPadding(0, 0, 0, shadowDepth.toInt()) // children are laid out on the face only
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val faceH = height - shadowDepth
        val dy = pressShift * press
        canvas.drawRoundRect(0f, shadowDepth, w, height.toFloat(), radius, radius, shadow)
        face.color = blend(up, down, press)
        canvas.drawRoundRect(0f, dy, w, faceH + dy, radius, radius, face)
        if (press > 0f) {
            // Round highlight behind the icon, as in portrait_resting_tapped.png (200 px on a 220 px face).
            highlight.alpha = (255 * 0.15f * press).toInt()
            canvas.drawCircle(w / 2f, faceH / 2f + dy, faceH * 0.455f, highlight)
        }
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + ((((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * t)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
