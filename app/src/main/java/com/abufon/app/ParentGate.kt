package com.abufon.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * The lock in front of the settings (v4 JSON states.parent.lockPanel): "¿Cuánto es 7 × 8?" with
 * two answer boxes and its own keypad (no system keyboard), on a scrim over the home screen.
 * A wrong answer shakes the boxes, clears them, shows "No es correcto. Prueba otra vez." and asks
 * a new question; after the third wrong answer the panel closes and the corner is ignored for 30 s
 * ([lockedOut]). It closes with the close button, a tap on the scrim, or 30 s without a touch.
 * Every opening asks a new question.
 */
object ParentGate {
    private const val IDLE_MS = 30_000L
    private const val LOCKOUT_MS = 30_000L
    private const val MAX_WRONG = 3

    /** elapsedRealtime until which the corner hold is ignored (after three wrong answers). */
    private var lockedUntil = 0L
    /** Wrong answers in a row, kept across panels so closing and reopening doesn't reset it. */
    private var wrongInARow = 0

    /** True for 30 s after three wrong answers: the home screen ignores the corner meanwhile. */
    fun lockedOut(): Boolean = SystemClock.elapsedRealtime() < lockedUntil

    /** Shows the lock and returns its dialog, so the caller can dismiss it when it goes away. */
    fun ask(activity: Activity, onPassed: () -> Unit): Dialog = LockPanel(activity, onPassed).show()

    /**
     * Debug previews: the designer's sample states, `lock` (7 × 8 with a 5 typed) and
     * `lockwrong` (after a wrong answer, now asking 6 × 4).
     */
    fun preview(activity: Activity, wrong: Boolean, onPassed: () -> Unit): Dialog =
        LockPanel(activity, onPassed).apply {
            if (wrong) { wrongCount = 1; showWrong(shake = false); a = 6; b = 4 } else { a = 7; b = 8; typed = "5" }
            refresh()
        }.show()

    private class LockPanel(private val activity: Activity, private val onPassed: () -> Unit) {
        private val handler = Handler(Looper.getMainLooper())
        private val navy = activity.getColor(R.color.gp_navy)
        private val hint = activity.getColor(R.color.gp_text_hint)
        private val error = activity.getColor(R.color.gp_error)
        private fun dp(v: Number) = activity.dpx(v)

        var a = 0
        var b = 0
        var typed = ""
        var wrongCount = 0
        /** The boxes show the error style until the next digit is typed. */
        private var errorStyle = false
        private var cursorOn = true

        private val giveUp = Runnable { dialog.dismissQuietly() }
        private val blink = object : Runnable {
            override fun run() {
                cursorOn = !cursorOn
                refreshCursor()
                handler.postDelayed(this, 530)
            }
        }

        private val dialog = PanelDialog(activity, activity.getColor(R.color.gp_scrim)) { restartIdle() }
        private val question = activity.parentText("", 38f, true, navy).apply { gravity = Gravity.CENTER }
        private val slots = LinearLayout(activity).apply { gravity = Gravity.CENTER_HORIZONTAL }
        private val slotDigits = List(2) { activity.parentText("", 52f, true, navy) }
        private val slotCursors = List(2) {
            View(activity).apply {
                background = GradientDrawable().apply { setColor(navy); cornerRadius = dp(1.5f).toFloat() }
            }
        }
        private val slotFrames = List(2) { i ->
            FrameLayout(activity).apply {
                addView(slotDigits[i], FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                addView(slotCursors[i], FrameLayout.LayoutParams(dp(3), dp(44), Gravity.CENTER))
            }
        }
        private val message = LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_alert); setColorFilter(error)
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(8) })
            addView(activity.parentText(activity.getString(R.string.lock_wrong), 22f, true, error))
        }
        private val keypad = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        private val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_lock_panel)
            elevation = dp(16).toFloat()
            // JSON padding [32, 44, 36, 44]: top, right, bottom, left.
            setPadding(dp(44), dp(32), dp(44), dp(36))
        }

        init {
            newQuestion()
            build()
            dialog.setOnDismissListener {
                handler.removeCallbacks(giveUp)
                handler.removeCallbacks(blink)
            }
        }

        fun show(): Dialog {
            refresh()
            dialog.show()
            restartIdle()
            if (ValueAnimator.areAnimatorsEnabled()) handler.postDelayed(blink, 530)
            return dialog
        }

        private fun restartIdle() {
            handler.removeCallbacks(giveUp)
            handler.postDelayed(giveUp, IDLE_MS)
        }

        private fun newQuestion() {
            val oldA = a; val oldB = b
            do { a = (3..9).random(); b = (3..9).random() } while (a == oldA && b == oldB)
        }

        private fun build() {
            // Header, 56 px: lock + "Solo para mayores" on the left, the close button on the right.
            val header = FrameLayout(activity)
            header.addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(activity).apply {
                    setImageResource(R.drawable.ic_lock); setColorFilter(hint)
                }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) })
                addView(activity.parentText(activity.getString(R.string.lock_caption), 20f, true, hint))
            }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.START))
            val close = activity.face(R.drawable.btn_round_outline, ImageView(activity).apply {
                setImageResource(R.drawable.ic_close); setColorFilter(navy)
                layoutParams = FrameLayout.LayoutParams(dp(24), dp(24))
            }) { dialog.dismissQuietly() }
            close.contentDescription = activity.getString(R.string.cd_close)
            (close.getChildAt(0).layoutParams as FrameLayout.LayoutParams).apply { width = dp(24); height = dp(24) }
            header.addView(close, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER_VERTICAL or Gravity.END))
            panel.addView(header, LinearLayout.LayoutParams(MATCH, dp(56)))

            panel.addView(question, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })

            slotFrames.forEachIndexed { i, f ->
                slots.addView(f, LinearLayout.LayoutParams(dp(80), dp(92)).apply { if (i > 0) marginStart = dp(14) })
            }
            panel.addView(slots, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(20) })
            panel.addView(message, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })

            // Keypad: 3 columns of 136 x 72 faces (+4 px shadow under btn_key / btn_key_ok), 12 px apart.
            val order = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")
            order.chunked(3).forEachIndexed { r, row ->
                val line = LinearLayout(activity)
                row.forEachIndexed { c, k ->
                    val key = when (k) {
                        "⌫" -> FrameLayout(activity).apply {
                            // btn_key_muted has no shadow: keep its face 72 px like the others.
                            background = InsetDrawable(activity.getDrawable(R.drawable.btn_key_muted), 0, 0, 0, dp(4))
                            isClickable = true; isFocusable = true
                            contentDescription = activity.getString(R.string.cd_backspace)
                            addView(ImageView(activity).apply {
                                setImageResource(R.drawable.ic_backspace); setColorFilter(navy)
                            }, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER).apply { bottomMargin = dp(4) })
                            setOnClickListener { Feedback.tap(it); backspace() }
                        }
                        "OK" -> activity.face(R.drawable.btn_key_ok,
                            activity.parentText(activity.getString(R.string.lock_ok), 30f, true, activity.getColor(R.color.gp_white))
                                .apply { translationY = dp(2).toFloat() }, // Fredoka sits high without font padding
                            bottomInset = dp(4)) { ok() }
                        else -> activity.face(R.drawable.btn_key,
                            activity.parentText(k, 32f, true, navy).apply { translationY = dp(3).toFloat() },
                            bottomInset = dp(4)) { digit(k) }
                    }
                    line.addView(key, LinearLayout.LayoutParams(dp(136), dp(76)).apply { if (c > 0) marginStart = dp(12) })
                }
                keypad.addView(line, LinearLayout.LayoutParams(WRAP, WRAP).apply { if (r > 0) topMargin = dp(8) })
            }
            panel.addView(keypad, LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL })
            layoutPanel()
        }

        /** 520 x 652, or 520 x 692 with the wrong-answer message (keypad 16 px under it instead of 28 under the boxes). */
        private fun layoutPanel() {
            val wrong = message.visibility == View.VISIBLE
            (keypad.layoutParams as LinearLayout.LayoutParams).topMargin = dp(if (wrong) 16 else 28)
            keypad.requestLayout()
            dialog.setPanel(panel, dp(520), dp(if (wrong) 692 else 652))
        }

        private fun digit(d: String) {
            if (typed.length >= 2) return
            typed += d
            errorStyle = false
            refresh()
        }

        private fun backspace() {
            typed = typed.dropLast(1)
            refresh()
        }

        private fun ok() {
            if (typed.isEmpty()) return
            if (typed.toIntOrNull() == a * b) {
                wrongInARow = 0
                dialog.dismissQuietly()
                onPassed()
                return
            }
            wrongCount++
            wrongInARow++
            if (wrongInARow >= MAX_WRONG) {
                wrongInARow = 0
                lockedUntil = SystemClock.elapsedRealtime() + LOCKOUT_MS
                dialog.dismissQuietly()
                return
            }
            showWrong(shake = true)
            refresh()
        }

        /** Wrong answer: shake the boxes (±12 px, 3 times, 300 ms), clear them, ask a new question. */
        fun showWrong(shake: Boolean) {
            typed = ""
            errorStyle = true
            newQuestion()
            if (message.visibility != View.VISIBLE) {
                message.visibility = View.VISIBLE
                layoutPanel()
            }
            if (shake && ValueAnimator.areAnimatorsEnabled()) {
                val s = dp(12).toFloat()
                ObjectAnimator.ofFloat(slots, View.TRANSLATION_X, 0f, s, -s, s, -s, s, -s, 0f).apply { duration = 300 }.start()
            }
            slots.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }

        fun refresh() {
            question.text = activity.getString(R.string.lock_question, a, b)
            for (i in 0..1) {
                slotDigits[i].text = typed.getOrNull(i)?.toString() ?: ""
                slotFrames[i].setBackgroundResource(when {
                    errorStyle -> R.drawable.bg_answer_slot_error
                    i < typed.length -> R.drawable.bg_answer_slot_active
                    else -> R.drawable.bg_answer_slot
                })
            }
            cursorOn = true
            refreshCursor()
        }

        /** The blinking bar in the box that takes the next digit (none when both are filled). */
        private fun refreshCursor() {
            for (i in 0..1) slotCursors[i].visibility = if (i == typed.length && cursorOn) View.VISIBLE else View.INVISIBLE
        }
    }

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
}
