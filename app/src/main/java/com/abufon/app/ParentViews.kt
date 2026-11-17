package com.abufon.app

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.Switch
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * Building blocks of the v4 parent area (lock panel, settings, time picker). Sizes are the
 * designer's px at 160 dpi (1 dp = 1 px), set in dp so they scale with the screen density and
 * never with the system font size.
 */

fun Context.dpx(v: Number): Int = (v.toFloat() * resources.displayMetrics.density).roundToInt()

/** Fredoka text in the parent area: [size] in designer px, SemiBold or Medium. */
fun Context.parentText(text: CharSequence, size: Float, semibold: Boolean, color: Int): TextView =
    TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
        typeface = ParentFonts.get(context, semibold)
        setTextColor(color)
        includeFontPadding = false
    }

object ParentFonts {
    private var semibold: Typeface? = null
    private var medium: Typeface? = null
    fun get(context: Context, bold: Boolean): Typeface? =
        if (bold) semibold ?: context.resources.getFont(R.font.fredoka_semibold).also { semibold = it }
        else medium ?: context.resources.getFont(R.font.fredoka_medium).also { medium = it }
}

/**
 * The platform [Switch] in the v4 look: a 76 x 44 pill track and a 36 px thumb with 4 px of track
 * around it, coloured by res/color/gp_switch_track.xml and gp_switch_thumb.xml (no Material
 * library). The platform switch places its thumb from the track's padding and the thumb's
 * intrinsic width, and its travel equals that width; so the thumb drawable reports a 32 px width
 * (the 76 - 36 - 2 x 4 px of travel) and draws its 36 px circle centred on it, and the track has
 * 6 px of side padding.
 */
fun Context.parentSwitch(): Switch = Switch(this).apply {
    val track = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpx(22).toFloat()
        setColor(Color.WHITE)
        setSize(dpx(76), dpx(44))
        setPadding(dpx(6), 0, dpx(6), 0)
    }
    trackDrawable = track
    thumbDrawable = SwitchThumb(dpx(36).toFloat(), dpx(32), dpx(44))
    trackTintList = getColorStateList(R.color.gp_switch_track)
    thumbTintList = getColorStateList(R.color.gp_switch_thumb)
    switchMinWidth = dpx(76)
    showText = false
    background = null
    text = null
    setPadding(0, 0, 0, 0)
}

/** The switch thumb: a circle of [diameter], drawn centred on a [logicalWidth] x [height] box. */
private class SwitchThumb(
    private val diameter: Float,
    private val logicalWidth: Int,
    private val height: Int,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(diameter * 0.06f, 0f, diameter * 0.03f, 0x33000000)
    }
    private var tint: ColorStateList? = null

    override fun setTintList(tint: ColorStateList?) { this.tint = tint; update() }
    override fun isStateful() = tint?.isStateful == true
    override fun onStateChange(state: IntArray): Boolean = update()
    private fun update(): Boolean {
        val t = tint ?: return false
        val c = t.getColorForState(state, t.defaultColor)
        if (c == paint.color) return false
        paint.color = c
        invalidateSelf()
        return true
    }

    override fun getIntrinsicWidth() = logicalWidth
    override fun getIntrinsicHeight() = height
    override fun draw(canvas: Canvas) =
        canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), diameter / 2, paint)
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * The 24-hour bar under the calling window (JSON settings.scheduleBar): a 22 px rounded track,
 * the window filled in gp_schedule_fill (split at midnight, see [ScheduleBar]), a 4 px
 * gp_now_marker line at the current time standing a little over the bar, and the 0 6 12 18 24
 * labels under it (Medium 16).
 */
class ScheduleBarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    var window: Schedule.Window = Schedule.Window(0, 0)
        set(v) { field = v; invalidate() }
    /** Minute of the day of "now", or -1 for no marker. */
    var nowMinute: Int = -1
        set(v) { field = v; invalidate() }

    private val barH = context.dpx(22).toFloat()
    private val overhang = context.dpx(4).toFloat()
    private val markerW = context.dpx(4).toFloat()
    private val labelGap = context.dpx(8).toFloat()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.gp_schedule_track) }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.gp_schedule_fill) }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.gp_now_marker) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.gp_text_secondary)
        textSize = context.dpx(16).toFloat()
        typeface = ParentFonts.get(context, false)
    }
    private val clip = Path()
    private val rect = RectF()

    private val labelH: Float get() = labelPaint.fontMetrics.let { it.descent - it.ascent }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (overhang + barH + overhang.coerceAtLeast(labelGap) + labelH).roundToInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val top = overhang
        rect.set(0f, top, w, top + barH)
        clip.reset()
        clip.addRoundRect(rect, barH / 2, barH / 2, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(rect, trackPaint)
        for (s in ScheduleBar.segments(window)) canvas.drawRect(s.start * w, top, s.end * w, top + barH, fillPaint)
        canvas.restore()
        if (nowMinute >= 0) {
            val x = (ScheduleBar.position(nowMinute) * w).coerceIn(markerW / 2, w - markerW / 2)
            canvas.drawRect(x - markerW / 2, 0f, x + markerW / 2, top + barH + overhang, markerPaint)
        }
        val fm = labelPaint.fontMetrics
        val baseline = top + barH + overhang.coerceAtLeast(labelGap) - fm.ascent
        for (hour in listOf(0, 6, 12, 18, 24)) {
            val text = hour.toString()
            val tw = labelPaint.measureText(text)
            val x = when (hour) {
                0 -> 0f
                24 -> w - tw
                else -> hour / 24f * w - tw / 2
            }
            canvas.drawText(text, x, baseline, labelPaint)
        }
    }
}

/**
 * A NumberPicker-style wheel (JSON parent.timePicker.wheels): 5 rows of 64 px; the middle row is
 * 48 SemiBold gp_navy, the next ones 32 gp_wheel_near, the outer ones 28 gp_wheel_far, blending
 * while it turns. It wraps around. Drag or fling to turn (it snaps to a row), or tap a row above
 * or below the middle to step to it.
 */
class WheelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    var values: List<String> = listOf("00")
        set(v) { field = v; position = 0f; invalidate() }
    /** The row in the middle, 0 until values.size. */
    var index: Int
        get() = Math.floorMod(position.roundToInt(), values.size)
        set(v) { anim?.cancel(); settling = null; position = v.toFloat(); invalidate(); updateDescription() }
    var onChange: (Int) -> Unit = {}
    /** The row the wheel shows, or is settling on while it still spins. Read this when saving. */
    val target: Int get() = settling?.let { Math.floorMod(it.roundToInt(), values.size) } ?: index

    private var position = 0f
    private var settling: Float? = null
    private val rowH = context.dpx(64).toFloat()
    private val navy = context.getColor(R.color.gp_navy)
    private val near = context.getColor(R.color.gp_wheel_near)
    private val far = context.getColor(R.color.gp_wheel_far)
    private val sizeCentre = context.dpx(48).toFloat()
    private val sizeNear = context.dpx(32).toFloat()
    private val sizeFar = context.dpx(28).toFloat()
    private val semibold = ParentFonts.get(context, true)
    private val medium = ParentFonts.get(context, false)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var anim: ValueAnimator? = null
    private var velocity: VelocityTracker? = null
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (rowH * 5).roundToInt())

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val first = floor(position).toInt() - 3
        for (k in first..first + 7) {
            val d = k - position
            val ad = abs(d)
            if (ad > 2.6f) continue
            val (size, color) = when {
                ad <= 1f -> lerp(sizeCentre, sizeNear, ad) to blend(navy, near, ad)
                ad <= 2f -> lerp(sizeNear, sizeFar, ad - 1f) to blend(near, far, ad - 1f)
                else -> sizeFar to far
            }
            paint.textSize = size
            paint.color = color
            paint.alpha = if (ad <= 2f) 255 else ((2.6f - ad) / 0.6f * 255).toInt().coerceIn(0, 255)
            paint.typeface = if (ad < 0.5f) semibold else medium
            val y = cy + d * rowH
            val fm = paint.fontMetrics
            canvas.drawText(values[Math.floorMod(k, values.size)], cx, y - (fm.ascent + fm.descent) / 2, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                anim?.cancel()
                velocity?.recycle()
                velocity = VelocityTracker.obtain()
                downY = event.y; lastY = event.y; dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(event.y - downY) > slop) dragging = true
                if (dragging) {
                    position -= (event.y - lastY) / rowH
                    invalidate()
                }
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                velocity?.addMovement(event)
                if (dragging) {
                    velocity?.computeCurrentVelocity(1000)
                    val rowsPerS = -(velocity?.yVelocity ?: 0f) / rowH
                    settle((position + rowsPerS * 0.18f).roundToInt().toFloat())
                } else {
                    // A tap: step to the tapped row.
                    val steps = ((event.y - height / 2f) / rowH).roundToInt().coerceIn(-2, 2)
                    if (steps != 0) { Feedback.tap(this); settle(position.roundToInt() + steps.toFloat()) }
                    performClick()
                }
                velocity?.recycle(); velocity = null
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                velocity?.recycle(); velocity = null
                settle(position.roundToInt().toFloat())
            }
        }
        velocity?.addMovement(event)
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun settle(target: Float) {
        anim?.cancel()
        val from = position
        if (from == target || !ValueAnimator.areAnimatorsEnabled()) { position = target; finish(); return }
        settling = target
        anim = ValueAnimator.ofFloat(from, target).apply {
            duration = (140 + 60 * abs(target - from)).toLong().coerceAtMost(600)
            interpolator = DecelerateInterpolator()
            addUpdateListener { position = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) finish() }
            })
            start()
        }
    }

    private fun finish() {
        settling = null
        // Keep the number small so it never loses precision.
        position = index.toFloat()
        invalidate()
        updateDescription()
        onChange(index)
    }

    private fun updateDescription() { contentDescription = values[index] }

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + ((((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * t)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/**
 * A full-screen dialog for the lock panel and the time picker: a [scrim] over the screen with a
 * panel centred on it (the designer's panel positions are the centred panel in both
 * orientations). A tap on the scrim cancels. Every touch is reported to [onTouch], since touches
 * in a dialog never reach the activity's onUserInteraction.
 */
class PanelDialog(
    activity: Activity,
    scrim: Int,
    private val onTouch: () -> Unit = {},
) : Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen) {
    val root = FrameLayout(activity).apply {
        setBackgroundColor(scrim)
        setOnClickListener { cancel() }
    }

    init {
        setContentView(root)
        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setWindowAnimations(0)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            attributes = attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    /** Puts [panel] in the middle; taps on it don't reach the scrim. */
    fun setPanel(panel: View, width: Int, height: Int) {
        panel.isClickable = true
        root.removeAllViews()
        root.addView(panel, FrameLayout.LayoutParams(width, height, Gravity.CENTER))
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        onTouch()
        return super.dispatchTouchEvent(ev)
    }

    /** Dismisses without throwing if the window is already gone. */
    fun dismissQuietly() {
        if (!isShowing) return
        try { dismiss() } catch (_: IllegalArgumentException) {}
    }
}

/** A key or button face: a background drawable with one centred child, clickable with feedback. */
fun Context.face(background: Int, child: View, bottomInset: Int = 0, onClick: () -> Unit): FrameLayout =
    FrameLayout(this).apply {
        setBackgroundResource(background)
        isClickable = true
        isFocusable = true
        addView(child, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ).apply { bottomMargin = bottomInset })
        setOnClickListener { Feedback.tap(it); onClick() }
    }
