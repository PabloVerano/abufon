package com.abufon.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import org.linphone.core.VideoDefinition
import org.linphone.mediastream.video.capture.CaptureTextureView

/** False when the system animator scale is 0 ("remove animations"): show static frames. */
val motionEnabled: Boolean get() = ValueAnimator.areAnimatorsEnabled()

/** Granny's photo, centre-cropped into a circle with a vertical bias; optional greyscale. */
class PhotoView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val bias: Float
    private val photo = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bitmap = (context.getDrawable(R.drawable.granny_photo) as BitmapDrawable).bitmap
    private val matrix = Matrix()

    /** Offline treatment: saturation 0 at 80 % opacity. */
    var greyscale = false
        set(value) {
            field = value
            photo.colorFilter = if (value) ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) else null
            photo.alpha = if (value) 204 else 255
            invalidate()
        }

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.PhotoView)
        bias = a.getFloat(R.styleable.PhotoView_verticalBias, 0.43f)
        a.recycle()
        photo.shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val size = minOf(w, h).toFloat()
        val scale = maxOf(size / bitmap.width, size / bitmap.height)
        matrix.setScale(scale, scale)
        matrix.postTranslate((w - bitmap.width * scale) / 2, (h - size) / 2 + (size - bitmap.height * scale) * bias)
        photo.shader.setLocalMatrix(matrix)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(width / 2f, height / 2f, minOf(width, height) / 2f, photo)
    }
}

/**
 * The tappable photo group. Its bounds are a square (frame + shadow padding), but only touches
 * inside the round frame count; anything in the corners falls through to the views underneath.
 */
class CircleTapLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    init {
        // Only ever wraps granny's photo: "Granny's photo".
        if (contentDescription == null) {
            contentDescription = context.getString(R.string.cd_photo, context.getString(R.string.grandma_name))
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val frame = getChildAt(0)
            val r = if (frame != null) minOf(frame.width, frame.height) / 2f else minOf(width, height) / 2f
            val dx = ev.x - width / 2f
            val dy = ev.y - height / 2f
            if (dx * dx + dy * dy > r * r) return false
        }
        return super.dispatchTouchEvent(ev)
    }
}

/**
 * Self-view. Linphone's own "occupy all space" mode stretches the camera picture, so this uses
 * its aspect-correct mode (the picture is letterboxed inside the view) and then resizes the view
 * itself to the picture's shape, large enough to cover the parent frame. The parent
 * ([RoundedClipLayout]) crops the overflow, so the frame is filled with no distortion.
 */
class SelfView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : CaptureTextureView(context, attrs) {
    init { mDisplayMode = DisplayMode.BLACK_BARS }

    private val ui = Handler(Looper.getMainLooper())
    /** The rotation Linphone last applied to the camera picture (-1 = not told yet). Logged only. */
    @Volatile private var texRotation = -1
    private var lastLogged = ""

    /**
     * The SDK rotates this view for us but never tells it the camera picture's size, and without
     * it the picture is simply stretched over the view. So ask the core for the preview size
     * (it is only known once the camera is running, and can change) and pass it on.
     * The core reports the sensor's size (640x480) whichever way the tablet is held, but on
     * screen the picture is upright: wide when the tablet is in landscape, tall in portrait.
     *
     * Safeguard: every 500 ms the shape is compared with what the view itself holds, not with a
     * cached copy, so it is put back if anything inside Linphone resets it (for example a camera
     * restart mid-call). Each change is also written to the call log to find the trigger.
     */
    private val syncCaptureSize = object : Runnable {
        override fun run() {
            applyAspect()
            ui.postDelayed(this, 500)
        }
    }

    /**
     * The camera picture's size. During a call the core's preview size reads 0x0 (and logs a
     * warning each time), and the camera then runs at the call's size (1280x720 on the Portal)
     * rather than the 640x480 preview, so in a call use the size actually being sent.
     */
    private fun pictureSize(): VideoDefinition? {
        val sent = LinphoneManager.activeCall()?.currentParams?.sentVideoDefinition
        if (sent != null && sent.width > 0 && sent.height > 0) return sent
        return LinphoneManager.coreOrNull?.currentPreviewVideoDefinition
    }

    private fun applyAspect() {
        val def = pictureSize()
        val long = maxOf(def?.width ?: 0, def?.height ?: 0)
        val short = minOf(def?.width ?: 0, def?.height ?: 0)
        val portrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        val w = if (portrait) short else long
        val h = if (portrait) long else short
        val held = previewVideoSize
        if (w > 0 && h > 0 && (held == null || held.width != w || held.height != h)) {
            setAspectRatio(w, h)
            requestLayout()
        }
        logState(def?.width ?: 0, def?.height ?: 0, w, h)
    }

    /** One call-log line whenever anything about the self-view's picture changes. */
    private fun logState(defW: Int, defH: Int, w: Int, h: Int) {
        if (def0(defW, defH)) return
        @Suppress("DEPRECATION")
        val display = when (display?.rotation) { 1 -> 90; 2 -> 180; 3 -> 270; 0 -> 0; else -> -1 }
        val held = previewVideoSize
        val rect = previewRectF
        val state = "selfview orient=${if (w < h) "port" else "land"} display=$display linphoneRot=$texRotation" +
            " preview=${defW}x$defH asked=${w}x$h held=${held?.width}x${held?.height}" +
            " view=${width}x$height picture=${rect?.width()?.toInt()}x${rect?.height()?.toInt()}"
        if (state != lastLogged) { lastLogged = state; CallLog.event(state) }

    }

    private fun def0(w: Int, h: Int) = w == 0 || h == 0

    /** Linphone calls this (from its camera thread) when it turns the picture. Recorded for the log. */
    override fun setRotation(rotation: Int) {
        texRotation = Math.floorMod(rotation, 360)
        super.setRotation(rotation)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ui.post(syncCaptureSize)
    }

    override fun onDetachedFromWindow() {
        ui.removeCallbacks(syncCaptureSize)
        super.onDetachedFromWindow()
    }

    override fun rotateToMatchDisplayOrientation() {
        super.rotateToMatchDisplayOrientation()
        coverParent()
    }

    private fun coverParent() {
        val parent = parent as? View ?: return
        val picture = previewRectF ?: return // where Linphone drew the camera picture in this view
        val pw = parent.width; val ph = parent.height
        if (pw == 0 || ph == 0 || picture.width() < 1f || picture.height() < 1f) return
        val scale = maxOf(pw / picture.width(), ph / picture.height())
        val w = Math.round(picture.width() * scale)
        val h = Math.round(picture.height() * scale)
        val lp = layoutParams as? FrameLayout.LayoutParams ?: return
        if (kotlin.math.abs(lp.width - w) <= 1 && kotlin.math.abs(lp.height - h) <= 1) return
        post {
            layoutParams = FrameLayout.LayoutParams(w, h, android.view.Gravity.CENTER)
        }
    }
}

/** Clips its children to a rounded rectangle (the self-view frame). */
class RoundedClipLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    init {
        val radius = 22f * resources.displayMetrics.density
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        clipToOutline = true
    }
}

/**
 * A designer button: the background drawable has a 3D shadow [shadowDepth] tall under the face.
 * Children (icon + label) are laid out on the face only, and drop by [pressShift] while pressed,
 * moving with the face. Every click gives haptic + sound feedback.
 */
class Button3D @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val shadowDepth: Int
    private val pressShift: Float

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.Button3D)
        shadowDepth = a.getDimensionPixelSize(R.styleable.Button3D_shadowDepth, 0)
        pressShift = a.getDimension(R.styleable.Button3D_pressShift, 0f)
        a.recycle()
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val faceH = MeasureSpec.makeMeasureSpec(measuredHeight - shadowDepth, MeasureSpec.EXACTLY)
        val faceW = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(faceW, faceH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) getChildAt(i).layout(0, 0, r - l, b - t - shadowDepth)
    }

    override fun dispatchSetPressed(pressed: Boolean) {
        val dy = if (pressed) pressShift else 0f
        for (i in 0 until childCount) getChildAt(i).translationY = dy
    }

    override fun performClick(): Boolean {
        Feedback.tap(this)
        return super.performClick()
    }
}

/** Expanding, fading rounded-rect glow behind the call/answer button. */
class GlowView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val radius: Float
    private val growBy: Float
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.gp_green) }
    private val rect = RectF()
    private var t = 0f
    private var animator: ValueAnimator? = null

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.GlowView)
        radius = a.getDimension(R.styleable.GlowView_cornerRadius, 0f)
        growBy = a.getDimension(R.styleable.GlowView_growBy, 0f)
        a.recycle()
    }

    fun start(periodMs: Long) {
        stop()
        if (!motionEnabled) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = periodMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun stop() {
        animator?.cancel(); animator = null
        t = 0f; invalidate()
    }

    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        if (animator == null) return
        // The view is the face plus growBy on every side; the glow grows from the face outwards.
        val g = growBy * t
        rect.set(growBy - g, growBy - g, width - growBy + g, height - growBy + g)
        paint.alpha = (255 * 0.48f * (1f - t)).toInt()
        canvas.drawRoundRect(rect, radius + g, radius + g, paint)
    }
}

/** Ring that fills clockwise from 12 o'clock while a hold-to-confirm button is held. */
class HoldRingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val stroke: Float
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()
    var progress = 0f
        set(value) { field = value; invalidate() }

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.HoldRingView)
        stroke = a.getDimension(R.styleable.HoldRingView_ringStroke, 12f)
        progressPaint.color = a.getColor(R.styleable.HoldRingView_progressColor, context.getColor(R.color.gp_hold_progress))
        track.color = a.getColor(R.styleable.HoldRingView_trackColor, context.getColor(R.color.gp_hold_track))
        a.recycle()
        track.strokeWidth = stroke
        progressPaint.strokeWidth = stroke
    }

    override fun onDraw(canvas: Canvas) {
        oval.set(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2)
        canvas.drawOval(oval, track)
        if (progress > 0f) canvas.drawArc(oval, -90f, 360f * progress, false, progressPaint)
    }
}

/**
 * Makes [this] a hold-to-confirm button: it must be held for [holdMs]. The [ring] fills while held
 * and resets on release. A short tap only calls [onShortTap] (to point at the hint).
 */
@SuppressLint("ClickableViewAccessibility")
fun View.holdToConfirm(ring: HoldRingView?, holdMs: Long = 1500, onShortTap: () -> Unit = {}, onConfirmed: () -> Unit) {
    var animator: ValueAnimator? = null
    var done = false
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                done = false
                v.isPressed = true
                Feedback.tap(v)
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = holdMs
                    interpolator = LinearInterpolator()
                    addUpdateListener { ring?.progress = it.animatedValue as Float }
                    addListener(object : AnimatorListenerAdapter() {
                        private var cancelled = false
                        override fun onAnimationCancel(animation: Animator) { cancelled = true }
                        override fun onAnimationEnd(animation: Animator) {
                            if (cancelled) return
                            done = true
                            v.isPressed = false
                            Feedback.confirm(v)
                            onConfirmed()
                        }
                    })
                    // With animations off, ValueAnimator ends instantly; keep the hold with a plain delay.
                    if (!motionEnabled) {
                        v.postDelayed({ if (v.isPressed && !done) { done = true; v.isPressed = false; onConfirmed() } }, holdMs)
                    } else start()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDone = done
                animator?.cancel(); animator = null
                v.isPressed = false
                ring?.progress = 0f
                if (!wasDone && e.actionMasked == MotionEvent.ACTION_UP) onShortTap()
                done = true // stops the no-animation fallback
            }
        }
        true
    }
}

/**
 * The guide bird. Plays one of the designer's named animations. Never tappable, except that the
 * home screen makes it a tap target for the tap reaction while resting (it cannot dial).
 */
class BirdView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ImageView(context, attrs) {
    private val ui = Handler(Looper.getMainLooper())
    private var frameLoop: Runnable? = null
    private var hop: ValueAnimator? = null

    init { isClickable = false; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun play(animation: String) {
        stop()
        visibility = VISIBLE
        if (!motionEnabled) {
            setImageResource(
                when (animation) {
                    "sleep" -> R.drawable.bird_sleep
                    "sing_hop", "point" -> R.drawable.bird_point
                    "breathe" -> R.drawable.bird_lying
                    "peek" -> R.drawable.bird_lying_peek
                    "stretch" -> R.drawable.bird_stretch
                    "letter" -> R.drawable.bird_letter
                    else -> R.drawable.bird_idle
                }
            )
            return
        }
        val d = resources.displayMetrics.density
        when (animation) {
            // v4: asleep on the plaque. scaleY 1 to 0.97 and 2 px down, 3.2 s there and back,
            // pivot at the belly (the bird rests on y = 171 of its 180 px).
            "breathe" -> {
                setImageResource(R.drawable.bird_lying)
                hop = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1600
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener {
                        val t = it.animatedValue as Float
                        // Set on every frame: this can start before the first layout (size 0).
                        pivotX = width * 0.5f
                        pivotY = height * 171f / 180f
                        scaleY = 1f - 0.03f * t
                        translationY = 2f * d * t
                    }
                    start()
                }
            }
            // v4 missed call: holding the letter, one 12 px hop every 3 s.
            "letter" -> {
                setImageResource(R.drawable.bird_letter)
                hop = ValueAnimator.ofFloat(0f, 3000f).apply {
                    duration = 3000
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        val ms = it.animatedValue as Float
                        translationY = if (ms < 400f) -12f * d * kotlin.math.sin(Math.PI * ms / 400.0).toFloat() else 0f
                    }
                    start()
                }
            }
            // v4 single frames; the home screen moves the bird itself during its sequences.
            "peek" -> setImageResource(R.drawable.bird_lying_peek)
            "stretch" -> setImageResource(R.drawable.bird_stretch)
            "idle" -> setImageResource(R.drawable.bird_idle)
            "nudge" -> loopFrames(R.drawable.bird_idle to 2100L, R.drawable.bird_point to 900L)
            "flap" -> loopFrames(R.drawable.bird_idle to 160L, R.drawable.bird_wing_up to 160L)
            "sing_hop" -> {
                loopFrames(R.drawable.bird_sing to 250L, R.drawable.bird_point to 250L)
                hop = ObjectAnimator.ofFloat(this, TRANSLATION_Y, 0f, -26f * d).apply {
                    duration = 500
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = AccelerateDecelerateInterpolator()
                    start()
                }
            }
            "sleep" -> setImageResource(R.drawable.bird_sleep)
            else -> setImageResource(R.drawable.bird_point) // "point"
        }
    }

    private fun loopFrames(vararg frames: Pair<Int, Long>) {
        var i = 0
        val r = object : Runnable {
            override fun run() {
                val (res, ms) = frames[i % frames.size]
                setImageResource(res)
                i++
                ui.postDelayed(this, ms)
            }
        }
        frameLoop = r
        r.run()
    }

    fun stop() {
        frameLoop?.let { ui.removeCallbacks(it) }; frameLoop = null
        hop?.cancel(); hop = null
        translationY = 0f
        scaleY = 1f
    }

    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
}

/** Collects looping animators of a screen so they can all be stopped on state change / pause. */
class Animations {
    private val running = mutableListOf<Animator>()

    /** Photo wiggle: -2.5° to 2.5°, 0.8 s loop. */
    fun wiggle(view: View) = add(ObjectAnimator.ofFloat(view, View.ROTATION, -2.5f, 2.5f).apply {
        duration = 400
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
    })

    /** Two rings behind the photo: scale 1 → [scaleTo] while fading 0.4 → 0, 1.8 s, staggered 0.9 s. */
    fun ripples(scaleTo: Float, vararg rings: View) = rings.forEachIndexed { i, ring ->
        ring.visibility = View.VISIBLE
        ring.alpha = 0f
        add(ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800
            startDelay = 900L * i
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                ring.scaleX = 1f + (scaleTo - 1f) * t
                ring.scaleY = ring.scaleX
                ring.alpha = 0.4f * (1f - t)
            }
        })
    }

    /** Music notes: fade in, drift 40 px left and ~85 px up while fading out, 1.6 s, staggered 0.8 s. */
    fun notes(vararg notes: View) = notes.forEachIndexed { i, note ->
        note.visibility = View.VISIBLE
        note.alpha = 0f
        val d = note.resources.displayMetrics.density
        add(ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600
            startDelay = 800L * i
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                note.translationX = -40f * d * t
                note.translationY = -(80f + 10f * i) * d * t
                note.alpha = if (t < 0.2f) t / 0.2f else 1f - (t - 0.2f) / 0.8f
            }
        })
    }

    private fun add(a: Animator) {
        if (!motionEnabled) return
        running += a
        a.start()
    }

    fun stopAll() {
        running.forEach { it.cancel() }
        running.clear()
    }
}
