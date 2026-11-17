package com.abufon.app

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * The parent's settings, v4 (JSON states.parent.settings, previews landscape_settings and
 * portrait_settings): a fixed header with "Ajustes" and "Listo", then cards — two columns in
 * landscape (status, pause, auto-answer | schedule, chirp), one in portrait (status, pause,
 * schedule, auto-answer, chirp). Every change is saved at once and the status card follows it
 * and the clock. Reached only through the lock ([ParentGate]; the activity is not exported). It
 * closes when it is left and after 3 minutes without a touch.
 */
class SettingsActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val autoClose = Runnable { finish() }
    /** Refreshes the status card and the "now" marker every minute, just past the minute. */
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            val now = System.currentTimeMillis()
            handler.postDelayed(this, 60_000 - now % 60_000 + 30)
        }
    }
    private var picker: PanelDialog? = null

    private val navy by lazy { getColor(R.color.gp_navy) }
    private val text by lazy { getColor(R.color.gp_parent_text) }
    private val hint by lazy { getColor(R.color.gp_text_hint) }
    private val name by lazy { getString(R.string.grandma_name) }

    // Views that refresh() updates. They are rebuilt when the orientation changes.
    private lateinit var callStatus: StatusRow
    private lateinit var ringStatus: StatusRow
    private lateinit var pauseSwitch: Switch
    private lateinit var scheduleSwitch: Switch
    private lateinit var scheduleDetails: View
    private lateinit var fromValue: TextView
    private lateinit var toValue: TextView
    private lateinit var scheduleBar: ScheduleBarView
    private lateinit var nowLegend: TextView
    private lateinit var secondSwitch: Switch
    private lateinit var secondHint: TextView
    private lateinit var secondDetails: View
    private lateinit var secondFromValue: TextView
    private lateinit var secondToValue: TextView
    private lateinit var secondBar: ScheduleBarView
    private lateinit var autoSwitch: Switch
    private lateinit var countdownRow: View
    private lateinit var countdownValue: TextView
    private lateinit var minus: View
    private lateinit var plus: View
    private lateinit var chirpSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goImmersive()
        // Only reachable through the gate (the activity is not exported).
        if (!intent.getBooleanExtra(EXTRA_UNLOCKED, false)) { finish(); return }
        if (BuildConfig.DEBUG) debugOrientation(intent.getStringExtra("orient"))
        build()
        if (BuildConfig.DEBUG && savedInstanceState == null) {
            when (intent.getStringExtra(EXTRA_PREVIEW)) {
                "timepicker" -> handler.post { pickTime(from = true, second = false) }
                "timepicker_to" -> handler.post { pickTime(from = false, second = false) }
            }
            // `--ei rotate_after <ms>`: turn the screen once, to check the layout (and an open picker) follow it.
            val rotateAfter = intent.getIntExtra("rotate_after", -1)
            if (rotateAfter >= 0) handler.postDelayed({
                requestedOrientation =
                    if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }, rotateAfter.toLong())
        }
    }

    /** Rotation does not recreate the activity (manifest configChanges): lay the cards out again. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        build()
        goImmersive()
    }

    override fun onResume() {
        super.onResume()
        goImmersive()
        restartAutoClose()
        handler.removeCallbacks(tick)
        tick.run()
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onUserInteraction() = restartAutoClose()

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    /** Leaving the screen (home button, a call coming in) locks it again. */
    override fun onStop() {
        handler.removeCallbacks(autoClose)
        dismissPicker()
        super.onStop()
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        dismissPicker()
        super.onDestroy()
    }

    override fun finish() {
        dismissPicker()
        super.finish()
    }

    private fun dismissPicker() {
        picker?.dismissQuietly()
        picker = null
    }

    private fun restartAutoClose() {
        handler.removeCallbacks(autoClose)
        handler.postDelayed(autoClose, AUTO_CLOSE_MS)
    }

    // ---- layout

    private fun build() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val status = statusCard()
        val pause = pauseCard()
        val schedule = scheduleCard()
        val auto = autoAnswerCard()
        val chirp = chirpCard()
        val exit = exitCard()

        val content: View = if (landscape) {
            LinearLayout(this).apply {
                addView(column(status, pause, auto), LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(column(schedule, chirp, exit), LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(48) })
            }
        } else {
            column(status, pause, schedule, auto, chirp, exit)
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(FrameLayout(this@SettingsActivity).apply {
                setPadding(dp(48), dp(28), dp(48), dp(28))
                addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
            }, ViewGroup.LayoutParams(MATCH, WRAP))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.gp_parent_bg))
            addView(header(), LinearLayout.LayoutParams(MATCH, dp(96)))
            addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        })
        refresh()
    }

    /** 96 px: "Ajustes" on the left, "Listo" (168 x 64) on the right, on white with a 1 px border at the bottom. */
    private fun header() = FrameLayout(this).apply {
        setBackgroundColor(getColor(R.color.gp_white))
        addView(View(context).apply { setBackgroundColor(getColor(R.color.gp_parent_border)) },
            FrameLayout.LayoutParams(MATCH, dp(1), Gravity.BOTTOM))
        addView(parentText(getString(R.string.settings_title), 40f, true, navy),
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = dp(48) })
        addView(face(R.drawable.btn_parent_primary, parentText(getString(R.string.settings_done), 28f, true, getColor(R.color.gp_white))) {
            finish()
        }, FrameLayout.LayoutParams(dp(168), dp(64), Gravity.CENTER_VERTICAL or Gravity.END).apply { marginEnd = dp(48) })
    }

    private fun column(vararg cards: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        cards.forEachIndexed { i, c ->
            addView(c, LinearLayout.LayoutParams(MATCH, WRAP).apply { if (i > 0) topMargin = dp(20) })
        }
    }

    /** bg_parent_card with 22 x 24 px padding. */
    private fun card(vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_parent_card)
        setPadding(dp(24), dp(22), dp(24), dp(22))
        children.forEach { addView(it, it.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(MATCH, WRAP)) }
    }

    private fun divider(top: Int, bottom: Int) = View(this).apply {
        setBackgroundColor(getColor(R.color.gp_parent_divider))
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply { topMargin = dp(top); bottomMargin = dp(bottom) }
    }

    private fun spaced(view: View, top: Int): View = view.apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top) }
    }

    /** A label (24 SemiBold, or 22 Medium for a sub-row) with a hint under it, and a switch on the right. */
    private fun switchRow(label: String, hintText: String, sub: Boolean, onChange: (Boolean) -> Unit): Triple<View, Switch, TextView> {
        val hintView = parentText(hintText, 18f, false, hint)
        val sw = parentSwitch()
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(parentText(label, if (sub) 22f else 24f, !sub, text))
            addView(hintView, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(3) })
        }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(16) })
            addView(sw, LinearLayout.LayoutParams(WRAP, WRAP))
            // The whole row toggles: an easier target than the switch alone.
            setOnClickListener { sw.toggle() }
        }
        val listener = android.widget.CompoundButton.OnCheckedChangeListener { _, on -> onChange(on); refresh() }
        sw.tag = listener // setQuietly() takes it off while refresh() sets the switch
        sw.setOnCheckedChangeListener(listener)
        return Triple(row, sw, hintView)
    }

    private class StatusRow(val view: View, val badge: View, val icon: ImageView, val title: TextView, val detail: TextView)

    private fun statusRow(iconRes: Int): StatusRow {
        val badge = FrameLayout(this)
        val icon = ImageView(this).apply { setImageResource(iconRes) }
        badge.addView(icon, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        val title = parentText("", 24f, true, text)
        val detail = parentText("", 18f, false, hint)
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(badge, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(16) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(detail, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(2) })
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        return StatusRow(row, badge, icon, title, detail)
    }

    private fun statusCard(): View {
        callStatus = statusRow(R.drawable.ic_call_out)
        ringStatus = statusRow(R.drawable.ic_call_in)
        return card(
            parentText(getString(R.string.status_header), 20f, true, hint),
            spaced(callStatus.view, 16),
            spaced(ringStatus.view, 16),
        )
    }

    private fun pauseCard(): View {
        val (row, sw, _) = switchRow(getString(R.string.pause_title), getString(R.string.pause_hint), sub = false) { Settings.paused = it }
        pauseSwitch = sw
        return card(row)
    }

    /** Desde / Hasta chips (72 px, gap 14): label 18 on the left, the time 32 on the right. */
    private fun chips(onFrom: () -> Unit, onTo: () -> Unit): Triple<View, TextView, TextView> {
        fun chip(label: String, onClick: () -> Unit): Pair<View, TextView> {
            val value = parentText("", 32f, true, navy)
            val chip = FrameLayout(this).apply {
                setBackgroundResource(R.drawable.bg_time_chip)
                isClickable = true
                setPadding(dp(22), 0, dp(22), 0)
                addView(parentText(label, 18f, false, hint), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.START))
                addView(value, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.END))
                setOnClickListener { Feedback.tap(it); onClick() }
            }
            return chip to value
        }
        val (from, fromV) = chip(getString(R.string.from), onFrom)
        val (to, toV) = chip(getString(R.string.to), onTo)
        val row = LinearLayout(this).apply {
            addView(from, LinearLayout.LayoutParams(0, dp(72), 1f))
            addView(to, LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginStart = dp(14) })
        }
        return Triple(row, fromV, toV)
    }

    private fun scheduleCard(): View {
        val (row, sw, _) = switchRow(getString(R.string.schedule_title), getString(R.string.schedule_hint), sub = false) {
            Settings.scheduleEnabled = it
        }
        scheduleSwitch = sw
        val (chipRow, f, t) = chips({ pickTime(from = true, second = false) }, { pickTime(from = false, second = false) })
        fromValue = f; toValue = t
        scheduleBar = ScheduleBarView(this)
        nowLegend = parentText("", 16f, false, getColor(R.color.gp_text_secondary)).apply {
            setCompoundDrawablesRelative(GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getColor(R.color.gp_now_marker))
                setBounds(0, 0, dp(9), dp(9))
            }, null, null, null)
            compoundDrawablePadding = dp(7)
            gravity = Gravity.CENTER_VERTICAL
        }

        val (secondRow, ssw, sHint) = switchRow(getString(R.string.second_window_title), "", sub = true) {
            Settings.secondWindowEnabled = it
        }
        secondSwitch = ssw; secondHint = sHint
        val (secondChips, sf, st) = chips({ pickTime(from = true, second = true) }, { pickTime(from = false, second = true) })
        secondFromValue = sf; secondToValue = st
        secondBar = ScheduleBarView(this)
        secondDetails = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(spaced(secondChips, 16))
            addView(spaced(secondBar, 12))
        }
        scheduleDetails = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(spaced(chipRow, 16))
            addView(spaced(scheduleBar, 12))
            addView(spaced(nowLegend, 18))
            addView(divider(16, 16))
            addView(secondRow)
            addView(secondDetails)
        }
        return card(row, scheduleDetails)
    }

    private fun autoAnswerCard(): View {
        val (row, sw, _) = switchRow(getString(R.string.autoanswer_title), getString(R.string.autoanswer_hint), sub = false) {
            Settings.autoAnswer = it
        }
        autoSwitch = sw
        fun round(icon: Int, cd: Int, onClick: () -> Unit) = face(R.drawable.btn_round_outline, ImageView(this).apply {
            setImageResource(icon); setColorFilter(navy)
        }, onClick = onClick).apply {
            contentDescription = getString(cd)
            (getChildAt(0).layoutParams as FrameLayout.LayoutParams).apply { width = dp(22); height = dp(22) }
        }
        minus = round(R.drawable.ic_minus, R.string.cd_minus) { Settings.autoAnswerSeconds = Settings.autoAnswerSeconds - 1; refresh() }
        plus = round(R.drawable.ic_plus, R.string.cd_plus) { Settings.autoAnswerSeconds = Settings.autoAnswerSeconds + 1; refresh() }
        countdownValue = parentText("", 28f, true, navy).apply { gravity = Gravity.CENTER; minWidth = dp(64) }
        val stepper = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(parentText(getString(R.string.countdown), 22f, false, text), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(minus, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(countdownValue, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(14); marginEnd = dp(14) })
            addView(plus, LinearLayout.LayoutParams(dp(56), dp(56)))
        }
        countdownRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(divider(18, 18))
            addView(stepper)
        }
        return card(row, countdownRow)
    }

    private fun chirpCard(): View {
        val (row, sw, _) = switchRow(getString(R.string.chirp_title), getString(R.string.chirp_hint), sub = false) { Settings.chirp = it }
        chirpSwitch = sw
        return card(row)
    }

    /**
     * The way out of Abufon without a computer: opens the Portal's own home screen or its
     * settings (e.g. to turn on USB debugging). Abufon stays the home app, so the Home button
     * or a restart brings it back, and granny's calls still come through.
     */
    private fun exitCard(): View {
        fun button(label: Int, onClick: () -> Unit) =
            face(R.drawable.btn_parent_secondary, parentText(getString(label), 22f, true, navy)) {
                leaveTo(onClick)
            }
        val buttons = LinearLayout(this).apply {
            addView(button(R.string.exit_portal_home) {
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setComponent(ComponentName(PORTAL_PACKAGE, PORTAL_HOME))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, LinearLayout.LayoutParams(0, dp(64), 1f))
            addView(button(R.string.exit_portal_settings) {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, LinearLayout.LayoutParams(0, dp(64), 1f).apply { marginStart = dp(14) })
        }
        return card(
            parentText(getString(R.string.exit_title), 24f, true, text),
            parentText(getString(R.string.exit_hint, name), 18f, false, hint).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(3) }
            },
            spaced(buttons, 18),
        )
    }

    /** Unpins the app if KIOSK pinned it, opens [open], and closes the settings. */
    private fun leaveTo(open: () -> Unit) {
        try {
            val am = getSystemService(android.app.ActivityManager::class.java)
            if (am.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE) stopLockTask()
        } catch (e: Exception) { android.util.Log.w("Abufon", "Unpin failed", e) }
        try { open() } catch (e: Exception) {
            android.util.Log.w("Abufon", "Exit target missing", e)
            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        CallLog.event("parent left Abufon")
        finish()
    }

    // ---- state

    /** Puts the stored settings and the clock on screen. Safe to call at any time. */
    private fun refresh() {
        if (!::callStatus.isInitialized) return
        val now = System.currentTimeMillis()
        val rules = Settings.rules()

        // Status, line 1: can she call now, and until / from when.
        val canCall = rules.canCall(now)
        callStatus.set(
            ok = canCall,
            title = getString(if (canCall) R.string.status_can_call else R.string.status_cannot_call, name),
            detail = when {
                rules.paused -> getString(R.string.status_paused)
                canCall -> rules.currentWindowEnd(now)?.let { TimeText.until(this, it) }
                else -> rules.nextCallOpening(now)?.let { TimeText.from(this, it) }
            },
        )
        // Line 2: does a call from her ring now, and is it answered by itself.
        val rings = rules.acceptsIncoming(now)
        ringStatus.set(
            ok = rings,
            title = getString(if (rings) R.string.status_rings else R.string.status_silent, name),
            detail = when {
                rules.paused -> getString(R.string.status_paused)
                !rings -> rules.nextIncomingOpening(now)?.let { TimeText.from(this, it) }
                Settings.autoAnswer -> getString(R.string.status_autoanswer, Settings.autoAnswerSeconds)
                else -> getString(R.string.status_autoanswer_off)
            },
        )

        pauseSwitch.setQuietly(Settings.paused)

        scheduleSwitch.setQuietly(Settings.scheduleEnabled)
        scheduleDetails.visibility = if (Settings.scheduleEnabled) View.VISIBLE else View.GONE
        val nowMinute = Schedule.minuteOfDayLocal(now)
        fromValue.text = Schedule.format(Settings.scheduleFrom)
        toValue.text = Schedule.format(Settings.scheduleTo)
        scheduleBar.window = Schedule.Window(Settings.scheduleFrom, Settings.scheduleTo)
        scheduleBar.nowMinute = nowMinute
        nowLegend.text = getString(R.string.now_marker, Schedule.format(nowMinute))

        val second = Settings.secondWindowEnabled
        secondSwitch.setQuietly(second)
        secondHint.text = getString(if (second) R.string.second_window_hint_on else R.string.second_window_hint_off)
        secondDetails.visibility = if (second) View.VISIBLE else View.GONE
        secondFromValue.text = Schedule.format(Settings.secondFrom)
        secondToValue.text = Schedule.format(Settings.secondTo)
        secondBar.window = Schedule.Window(Settings.secondFrom, Settings.secondTo)
        secondBar.nowMinute = nowMinute

        autoSwitch.setQuietly(Settings.autoAnswer)
        countdownRow.visibility = if (Settings.autoAnswer) View.VISIBLE else View.GONE
        val seconds = Settings.autoAnswerSeconds
        countdownValue.text = getString(R.string.countdown_value, seconds)
        minus.isEnabled = seconds > 1; minus.alpha = if (seconds > 1) 1f else 0.4f
        plus.isEnabled = seconds < 60; plus.alpha = if (seconds < 60) 1f else 0.4f

        chirpSwitch.setQuietly(Settings.chirp)
    }

    private fun StatusRow.set(ok: Boolean, title: String, detail: String?) {
        this.title.text = title
        this.detail.text = detail ?: ""
        this.detail.visibility = if (detail == null) View.GONE else View.VISIBLE
        badge.setBackgroundResource(if (ok) R.drawable.bg_status_ok else R.drawable.bg_status_off)
        icon.setColorFilter(getColor(if (ok) R.color.gp_status_ok_icon else R.color.gp_status_off_icon))
    }

    /** Sets a switch without running its listener (refresh() must not write the settings). */
    private fun Switch.setQuietly(on: Boolean) {
        if (isChecked == on) return
        val listener = tag as? android.widget.CompoundButton.OnCheckedChangeListener
        setOnCheckedChangeListener(null)
        isChecked = on
        jumpDrawablesToCurrentState()
        setOnCheckedChangeListener(listener)
    }

    /** Opens the time picker for a Desde / Hasta chip; Guardar saves at once. */
    private fun pickTime(from: Boolean, second: Boolean) {
        if (picker?.isShowing == true || isFinishing) return
        val current = when {
            second && from -> Settings.secondFrom
            second -> Settings.secondTo
            from -> Settings.scheduleFrom
            else -> Settings.scheduleTo
        }
        picker = TimePicker.show(
            this,
            title = getString(if (from) R.string.from else R.string.to),
            hint = getString(if (from) R.string.picker_from_hint else R.string.picker_to_hint),
            minuteOfDay = current,
            onTouch = { restartAutoClose() },
        ) { minute ->
            when {
                second && from -> Settings.secondFrom = minute
                second -> Settings.secondTo = minute
                from -> Settings.scheduleFrom = minute
                else -> Settings.scheduleTo = minute
            }
            refresh()
        }.also { it.setOnDismissListener { _ -> if (picker === it) picker = null } }
    }

    private fun dp(v: Int) = dpx(v)

    companion object {
        private const val EXTRA_UNLOCKED = "unlocked"
        private const val PORTAL_PACKAGE = "com.facebook.alohaapps.launcher"
        private const val PORTAL_HOME = "com.facebook.aloha.app.home.touch.HomeActivity"
        private const val EXTRA_PREVIEW = "preview"
        private const val AUTO_CLOSE_MS = 3 * 60_000L
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /** Opens the settings (after the lock). [orient] and [preview] are debug-only screenshot hooks. */
        fun open(context: Context, orient: String? = null, preview: String? = null, rotateAfter: Int = -1) {
            val i = Intent(context, SettingsActivity::class.java).putExtra(EXTRA_UNLOCKED, true)
            if (BuildConfig.DEBUG) {
                i.putExtra("orient", orient); i.putExtra(EXTRA_PREVIEW, preview); i.putExtra("rotate_after", rotateAfter)
            }
            context.startActivity(i)
        }
    }
}
