package com.abufon.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ImageSpan
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import org.linphone.core.Account
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.RegistrationState

/**
 * The launcher. What it shows is a [HomeScreen] (see there for the priority):
 *  HOME    — photo + big "Llamar" button, the bird nudging towards it
 *  MISSED  — the same, plus the letter cue: badge on the photo, the bird holding a letter,
 *            "<name> llamó / Hoy a las 15:20"; until the next call that connects
 *  FAILED  — the same as HOME, plus "No contestó"; stays until she calls again
 *  RESTING — outside the calling hours: dusk, the navy plaque instead of the call button, the
 *            bird asleep on it, the photo tinted with a moon badge
 *  PAUSED  — "Pausar llamadas" is on: as RESTING with pause icons
 *  OFFLINE — after 5 s without SIP registration: grey photo, sleeping bird, "Reintentar"
 * Tapping the photo calls too (HOME, MISSED and FAILED). While RESTING or PAUSED nothing can
 * dial: the plaque, the photo and the bird only make the bird peek.
 * When the calling hours begin or end while this screen is visible, it plays the wake-up
 * sequence or the quiet fade to resting; entering a state any other way is immediate.
 * Holding the top-left corner for 3 s opens the parent's lock: a ring appears after 1 s and fills.
 */
class HomeActivity : Activity() {
    private var screen = HomeScreen.HOME
    /** No connection for 5 s (or Linphone is not running). */
    private var offline = false
    /** The last call she placed was not answered ("No contestó"). */
    private var failed = false
    private val handler = Handler(Looper.getMainLooper())
    private val goOffline = Runnable { if (!registered()) { offline = true; update() } }
    private val anims = Animations()
    private var resumed = false
    private var scheduleNow = System.currentTimeMillis()

    /** The wake-up or closing sequence in progress. [screen] is already its final screen. */
    private var transition: Timeline? = null
    private var tapTimeline: Timeline? = null
    private var tapLockedUntil = 0L

    /**
     * The schedule check: every 30 s and exactly at the next boundary minute. Only a change found
     * here (the hours beginning or ending while home is visible) plays a sequence.
     */
    private val scheduleTick = Runnable { update(animate = true); armScheduleTick() }

    private var gate: android.app.Dialog? = null
    private var lockTimeline: Timeline? = null
    private val showLockRing = Runnable {
        if (gate?.isShowing == true || !resumed) return@Runnable
        val ring = findViewById<HoldRingView>(R.id.lock_ring)
        ring.progress = 0f
        findViewById<View>(R.id.lock_hold).visibility = View.VISIBLE
        lockTimeline = Timeline(LOCK_FILLS_MS, { ms -> ring.progress = ms / LOCK_FILLS_MS.toFloat() }) {
            cancelLockHold()
            if (gate?.isShowing == true || !resumed) return@Timeline
            Feedback.confirm(window.decorView)
            gate = ParentGate.ask(this) { SettingsActivity.open(this) }
        }.start()
    }

    /** Debug builds only: force a screen for screenshots, e.g. `--es preview offline`. */
    private var preview: String? = null
    /** Debug: with `preview wakeup|closing`, `--ei at <ms>` freezes the sequence at that moment. */
    private var previewAt = -1
    private val previewAction = Runnable { runPreviewAction() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.screen_home)
        goImmersive()
        AbufonService.start(this)

        findViewById<View>(R.id.call_button).setOnClickListener { callGranny() }
        findViewById<View>(R.id.photo_group).setOnClickListener {
            when {
                screen.resting -> tapReaction(it)
                screen.canDial -> { Feedback.tap(it); callGranny() }
            }
        }
        // Resting / paused: the plaque and the bird only make the bird peek. Neither can dial.
        findViewById<View>(R.id.rest_plaque).setOnClickListener { tapReaction(it) }
        findViewById<View>(R.id.bird).apply {
            setOnClickListener { tapReaction(it) }
            isClickable = false // show() makes it a tap target while resting only
        }
        // Offline "Reintentar" only re-checks the connection; it never places a call.
        findViewById<View>(R.id.retry_button).setOnClickListener { retry() }
        requestPermissionsIfNeeded()
        val saved = savedInstanceState?.getString(KEY_STATE)
        if (saved != null) {
            // Keep what was shown across rotation; no sequence is played for it.
            offline = savedInstanceState.getBoolean(KEY_OFFLINE)
            failed = savedInstanceState.getBoolean(KEY_FAILED)
            preview = savedInstanceState.getString(KEY_PREVIEW)
            previewAt = savedInstanceState.getInt(KEY_PREVIEW_AT, -1)
            show(HomeScreen.valueOf(saved))
            if (preview != null) startPreview()
        } else {
            handleIntent(intent)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_STATE, screen.name)
        outState.putBoolean(KEY_OFFLINE, offline)
        outState.putBoolean(KEY_FAILED, failed)
        preview?.let { outState.putString(KEY_PREVIEW, it); outState.putInt(KEY_PREVIEW_AT, previewAt) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (BuildConfig.DEBUG) {
            when (intent.getStringExtra("orient")) {
                "land" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                "port" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                "auto" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            }
            // `--es missed set|clear`: write or clear the missed-call log without a real call.
            when (intent.getStringExtra("missed")) {
                "set" -> MissedCall.record()
                "clear" -> MissedCall.clear()
            }
            android.util.Log.i("Abufon", "Missed call log: " + (MissedCall.latest?.let { TimeText.missedSubtitle(this, it) } ?: "empty"))
            when (val p = intent.getStringExtra(EXTRA_PREVIEW)) {
                "incoming" -> {
                    startActivity(Intent(this, IncomingCallActivity::class.java)
                        .putExtra(IncomingCallActivity.EXTRA_PREVIEW, true)
                        .putExtra("orient", intent.getStringExtra("orient")))
                    return
                }
                "ring_test" -> { LinphoneManager.debugRing(delayMs = 8_000); }
                "settings", "timepicker", "timepicker_to" -> {
                    SettingsActivity.open(this, intent.getStringExtra("orient"), p.takeIf { it != "settings" },
                        intent.getIntExtra("rotate_after", -1))
                    return
                }
                "calling", "incall", "novideo", "waiting", "dropped", "arrives", "answered" -> {
                    startActivity(Intent(this, CallActivity::class.java)
                        .putExtra(CallActivity.EXTRA_PREVIEW, p)
                        .putExtra("level", intent.getIntExtra("level", -1))
                        .putExtra("orient", intent.getStringExtra("orient")))
                    return
                }
            }
            handler.removeCallbacks(previewAction)
            findViewById<View>(R.id.lock_hold).visibility = View.GONE // a `lockhold` preview before this one
            preview = intent.getStringExtra(EXTRA_PREVIEW)?.lowercase()?.takeIf { previewScreen(it) != null }
            previewAt = intent.getIntExtra("at", -1)
        }
        failed = intent.getBooleanExtra(EXTRA_FAILED, false)
        intent.removeExtra(EXTRA_FAILED)
        if (preview != null) startPreview() else show(target())
    }

    /**
     * Debug previews of this screen (`--es preview <name>`, with `--es orient land|port`):
     * home, missed, failed, offline, resting, paused; tapped (the tap reaction, held);
     * wakeup and closing (the sequence plays 2 s after the start, or is frozen at `--ei at <ms>`);
     * lockhold (the corner ring, half full); lock and lockwrong (the lock panel over home, with the
     * designer's sample questions; answering it opens the settings). Missed shows a sample time
     * and leaves the log alone. The settings have their own: settings, timepicker, timepicker_to.
     */
    private fun previewScreen(name: String): HomeScreen? = when (name) {
        "home", "closing", "lockhold", "lock", "lockwrong" -> HomeScreen.HOME
        "missed" -> HomeScreen.MISSED
        "failed" -> HomeScreen.FAILED
        "offline" -> HomeScreen.OFFLINE
        "resting", "tapped", "wakeup" -> HomeScreen.RESTING
        "paused" -> HomeScreen.PAUSED
        else -> null
    }

    private fun startPreview() {
        val name = preview ?: return
        show(previewScreen(name) ?: return)
        handler.removeCallbacks(previewAction)
        when (name) {
            "tapped" -> tapPose(pressed = 1f, note = 0.75f, noteAlpha = 1f)
            "lockhold" -> {
                findViewById<HoldRingView>(R.id.lock_ring).progress = 0.5f
                findViewById<View>(R.id.lock_hold).visibility = View.VISIBLE
            }
            "wakeup", "closing" ->
                if (previewAt >= 0) runPreviewAction() else handler.postDelayed(previewAction, 2_000)
            // startPreview runs again on every onResume: never stack a second lock.
            "lock", "lockwrong" -> if (gate?.isShowing != true) {
                gate = ParentGate.preview(this, wrong = name == "lockwrong") { SettingsActivity.open(this) }
            }
        }
    }

    private fun runPreviewAction() {
        when (preview) {
            "wakeup" -> wakeUp(HomeScreen.HOME, freezeAt = previewAt)
            "closing" -> closeDown(HomeScreen.RESTING, freezeAt = previewAt)
        }
    }

    override fun onResume() {
        super.onResume()
        goImmersive()
        if (preview == null) applyNight()
        resumed = true
        if (preview != null) startPreview() else startAnimations()
        if (BuildConfig.KIOSK) startKiosk()
        // A call declined outside the hours is logged while this screen is up; a newer missed call
        // also replaces "No contestó".
        MissedCall.onChange = {
            if (MissedCall.latest != null) failed = false
            update()
        }
        attachCore()
        // Whatever changed while away (settings, the clock, a call) is shown at once, no sequence.
        update()
        armScheduleTick()
    }

    private fun armScheduleTick() {
        handler.removeCallbacks(scheduleTick)
        val now = System.currentTimeMillis()
        // 30 ms past the boundary, so the check lands inside the new minute.
        val toBoundary = Settings.nextCallChange(now)?.let { it - now + 30 }
        handler.postDelayed(scheduleTick, minOf(SCHEDULE_TICK_MS, toBoundary ?: SCHEDULE_TICK_MS).coerceAtLeast(30))
    }

    /** The screen the app's state asks for right now. */
    private fun target(): HomeScreen {
        scheduleNow = System.currentTimeMillis() // one clock reading for the decision and the texts
        return HomeScreen.pick(
            offline = offline,
            paused = Settings.paused,
            canCall = Settings.canCallNow(scheduleNow),
            failed = failed,
            missed = MissedCall.latest != null,
        )
    }

    /**
     * Day and night for the screen itself. During the calling hours the screen is held on, so the
     * Portal never times out into its photo slideshow. Outside them (or while paused) it drops to
     * minimum brightness and is let go: after the Portal's timeout it switches off (with the
     * Portal's screensaver turned off; deploy.sh does that). Granny's calls still turn it on.
     */
    private fun applyNight() {
        val day = Settings.canCallNow()
        if (day) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.applyNightBrightness()
        NightBrightness.apply(this, night = !day)
    }

    /**
     * Shows the screen the state asks for, if it changed. [animate] is set only by the schedule
     * check: then RESTING -> home plays the wake-up and home -> RESTING the fade to resting.
     */
    private fun update(animate: Boolean = false) {
        if (preview != null) return
        applyNight()
        val to = target()
        if (to == screen) {
            // Same screen: the opening time, hoy/mañana and Hoy/Ayer can still have changed.
            if (transition == null) { updateTexts(); updateBadge() }
            return
        }
        when {
            animate && resumed && screen == HomeScreen.RESTING && (to == HomeScreen.HOME || to == HomeScreen.MISSED) -> wakeUp(to)
            animate && resumed && screen.canDial && to == HomeScreen.RESTING -> closeDown(to)
            else -> show(to)
        }
    }

    /**
     * Hold the top-left corner (120 x 120) for 3 s to reach the parent's lock, in every state:
     * after 1 s the ring with the lock appears and fills over the next 2 s. Releasing hides it.
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        val corner = 120 * resources.displayMetrics.density
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN ->
                // After three wrong answers the lock ignores the corner for 30 s.
                if (ev.x < corner && ev.y < corner && !ParentGate.lockedOut()) handler.postDelayed(showLockRing, LOCK_APPEARS_MS)
            android.view.MotionEvent.ACTION_MOVE ->
                if (ev.x > corner || ev.y > corner) cancelLockHold()
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL,
            android.view.MotionEvent.ACTION_POINTER_DOWN -> cancelLockHold()
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun cancelLockHold() {
        handler.removeCallbacks(showLockRing)
        lockTimeline?.cancel(); lockTimeline = null
        if (preview != "lockhold") findViewById<View>(R.id.lock_hold).visibility = View.GONE
    }

    /** Hook up to Linphone, or show the offline screen and wait for it if it isn't running. */
    private fun attachCore() {
        val core = LinphoneManager.coreOrNull
        if (core == null) {
            // Linphone isn't running (start-up failed or safe mode): stay a working launcher.
            offline = true
            update()
            AbufonApp.onLinphoneReady = { if (resumed) attachCore() }
            return
        }
        core.removeListener(listener) // onResume can run again when Linphone comes up late
        core.addListener(listener)
        core.ensureRegistered()
        onRegistration(LinphoneManager.registrationState)
        if (preview != null) return
        // If a call is already in progress (e.g. app reopened), jump straight to it.
        if (LinphoneManager.ringingCall() != null) {
            startActivity(Intent(this, IncomingCallActivity::class.java))
        } else if (LinphoneManager.activeCall() != null) {
            startActivity(Intent(this, CallActivity::class.java))
        }
    }

    override fun onPause() {
        resumed = false
        // A sequence does not outlive the screen: jump to its end.
        if (transition != null || tapTimeline != null) show(screen)
        stopAnimations()
        MissedCall.onChange = null
        AbufonApp.onLinphoneReady = null
        LinphoneManager.coreOrNull?.removeListener(listener)
        handler.removeCallbacks(goOffline)
        handler.removeCallbacks(scheduleTick)
        handler.removeCallbacks(previewAction)
        cancelLockHold()
        // The gate must not outlive this activity (rotation, an incoming call taking over).
        gate?.let { if (it.isShowing) it.dismiss() }
        gate = null
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    // Launcher: there is nowhere to go back to.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = Unit

    private fun registered() = LinphoneManager.registrationState == RegistrationState.Ok

    private fun onRegistration(reg: RegistrationState) {
        val ok = reg == RegistrationState.Ok
        if (preview != null) return
        handler.removeCallbacks(goOffline)
        if (ok) {
            if (offline) { offline = false; update() }
        } else {
            // Avoid flashing "Sin conexión" during start-up or brief re-registrations.
            handler.postDelayed(goOffline, OFFLINE_DELAY_MS)
        }
    }

    private fun callGranny() {
        // Only the day screens can dial; not while a sequence is still running, and never from a
        // debug preview (those are for screenshots).
        if (!screen.canDial || transition != null || preview != null) return
        if (!hasPermissions()) { requestPermissionsIfNeeded(); return }
        if (!registered()) { offline = true; update(); return }
        if (!Settings.canCallNow()) { update(); return }
        if (LinphoneManager.callGranny()) startActivity(Intent(this, CallActivity::class.java))
    }

    private fun retry() {
        val core = LinphoneManager.coreOrNull
        if (core != null) { core.refreshRegisters(); return }
        // Linphone never started: try now. onLinphoneReady re-runs onResume if it comes up.
        AbufonApp.retryNow()
    }

    /** Shows [newScreen] in its final, settled look, ending any sequence. */
    private fun show(newScreen: HomeScreen) {
        transition?.cancel(); transition = null
        tapTimeline?.cancel(); tapTimeline = null
        screen = newScreen
        val off = newScreen == HomeScreen.OFFLINE
        val rest = newScreen.resting
        val dial = newScreen.canDial
        // "No contestó" is about the call just placed; it does not come back after a rest or an outage.
        if (!dial) failed = false

        findViewById<ImageView>(R.id.bg).setImageResource(if (off) R.drawable.bg_offline else R.drawable.bg_day)
        findViewById<View>(R.id.photo_frame).setBackgroundResource(
            if (off) R.drawable.bg_photo_frame_offline else R.drawable.bg_photo_frame
        )
        findViewById<PhotoView>(R.id.photo).greyscale = off

        visible(rest, R.id.bg_rest, R.id.photo_tint, R.id.rest_plaque, R.id.rest_title, R.id.rest_subtitle)
        visible(dial, R.id.call_button, R.id.glow)
        visible(newScreen == HomeScreen.FAILED, R.id.fail_title, R.id.fail_subtitle)
        visible(newScreen == HomeScreen.MISSED, R.id.missed_title, R.id.missed_subtitle)
        visible(off, R.id.off_title, R.id.off_subtitle, R.id.retry_button, R.id.bird_offline)
        visible(!off, R.id.bird)
        visible(false, R.id.rest_note)
        setRestAmount(1f)
        for (id in intArrayOf(R.id.fail_title, R.id.fail_subtitle, R.id.missed_title, R.id.missed_subtitle)) {
            findViewById<View>(id).alpha = 1f
        }
        findViewById<View>(R.id.call_button).isEnabled = dial
        setButtonBlend(if (rest) 1f else 0f)
        findViewById<RestPlaqueView>(R.id.rest_plaque).press = 0f
        findViewById<ImageView>(R.id.rest_note).imageTintList = getColorStateList(R.color.gp_rest_note)
        findViewById<ImageView>(R.id.rest_plaque_icon).apply {
            val paused = newScreen == HomeScreen.PAUSED
            setImageResource(if (paused) R.drawable.ic_pause else R.drawable.ic_moon_stars)
            imageTintList = getColorStateList(if (paused) R.color.gp_rest_pause_icon else R.color.gp_rest_moon)
        }
        findViewById<BirdView>(R.id.bird).apply {
            alpha = 1f
            isClickable = rest // a tap target for the tap reaction only
        }
        updateBadge()

        findViewById<TextView>(R.id.fail_title).setText(R.string.failed_title)
        findViewById<TextView>(R.id.fail_subtitle).setText(R.string.failed_subtitle)
        findViewById<TextView>(R.id.off_subtitle).setText(R.string.offline_subtitle)
        // Title with the no-wifi icon before the text (60 px, navy, 20 px gap).
        val title = findViewById<TextView>(R.id.off_title)
        val d = resources.displayMetrics.density
        val icon = getDrawable(R.drawable.ic_wifi_off)!!.mutate().apply {
            setTint(getColor(R.color.gp_navy))
            setBounds(0, 0, (60 * d).toInt(), (60 * d).toInt())
        }
        title.text = SpannableString("  " + getString(R.string.offline_title)).apply {
            setSpan(ImageSpan(icon, ImageSpan.ALIGN_BOTTOM), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        updateTexts()
        if (resumed) startAnimations()
    }

    /** When granny's missed call arrived, or null. The `missed` preview shows a sample (today, 15:20). */
    private fun missedAt(): Long? {
        if (preview == "missed") {
            return java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 15); set(java.util.Calendar.MINUTE, 20)
            }.timeInMillis
        }
        return if (preview != null) null else MissedCall.latest
    }

    /**
     * The badge on the photo: moon (resting), pause (paused) or letter (a missed call). The letter
     * also stays on "No contestó", where the text and the bird are taken by that message.
     */
    private fun updateBadge(forScreen: HomeScreen = screen) {
        val badge = findViewById<ImageView>(R.id.photo_badge)
        val d = resources.displayMetrics.density
        fun set(background: Int, icon: Int, iconPx: Int, tint: Int) {
            badge.setBackgroundResource(background)
            badge.setImageResource(icon)
            badge.imageTintList = getColorStateList(tint)
            val pad = ((104 - iconPx) / 2 * d).toInt() // the badge is 104 px
            badge.setPadding(pad, pad, pad, pad)
            badge.visibility = View.VISIBLE
        }
        when {
            forScreen == HomeScreen.RESTING -> set(R.drawable.bg_badge_moon, R.drawable.ic_moon, 48, R.color.gp_rest_moon)
            forScreen == HomeScreen.PAUSED -> set(R.drawable.bg_badge_moon, R.drawable.ic_pause, 44, R.color.gp_white)
            forScreen == HomeScreen.MISSED || (forScreen == HomeScreen.FAILED && missedAt() != null) ->
                set(R.drawable.bg_badge_mail, R.drawable.ic_mail, 48, R.color.gp_white)
            else -> badge.visibility = View.GONE
        }
    }

    /** The texts that depend on the clock: resting/paused and the missed call. */
    private fun updateTexts() {
        if (screen.resting) {
            val paused = screen == HomeScreen.PAUSED
            findViewById<TextView>(R.id.rest_title).setText(if (paused) R.string.paused_title else R.string.resting_title)
            val opens = Settings.nextCallOpening(scheduleNow)
            findViewById<TextView>(R.id.rest_subtitle).text = when {
                paused -> getString(R.string.paused_subtitle)
                opens != null -> TimeText.restingSubtitle(this, opens, scheduleNow)
                // Debug preview with no schedule set: the designer's sample.
                preview != null -> getString(R.string.resting_subtitle_today, TimeText.withArticle(this, 13 * 60))
                else -> ""
            }
        }
        if (screen == HomeScreen.MISSED) {
            findViewById<TextView>(R.id.missed_title).text = getString(R.string.missed_title, getString(R.string.grandma_name))
            findViewById<TextView>(R.id.missed_subtitle).text =
                missedAt()?.let { TimeText.missedSubtitle(this, it, System.currentTimeMillis()) } ?: ""
        }
    }

    /**
     * How much of the dusk scene shows over the day scene: 1 = resting, 0 = day. The resting texts
     * share their place with the day's texts ("No contestó", the missed call), so they only show
     * in the half nearer resting and [dayTexts] in the other half; they never overlap.
     */
    private fun setRestAmount(r: Float, vararg dayTexts: Int, badge: Boolean = true) {
        for (id in intArrayOf(R.id.bg_rest, R.id.photo_tint, R.id.rest_plaque)) {
            findViewById<View>(id).alpha = r
        }
        if (badge) findViewById<View>(R.id.photo_badge).alpha = r
        val restText = (2f * r - 1f).coerceIn(0f, 1f)
        findViewById<View>(R.id.rest_title).alpha = restText
        findViewById<View>(R.id.rest_subtitle).alpha = restText
        for (id in dayTexts) findViewById<View>(id).alpha = (1f - 2f * r).coerceIn(0f, 1f)
    }

    /**
     * The contents of the two buttons while one blends into the other (wakeup_sequence.png, frame
     * 2: a bare button between navy and green): the plaque's icon is there for the last third
     * towards resting ([r] = 1), the phone and "Llamar" for the last third towards day (0).
     */
    private fun setButtonBlend(r: Float) {
        findViewById<View>(R.id.rest_plaque_icon).alpha = ((r - 0.65f) / 0.35f).coerceIn(0f, 1f)
        (findViewById<View>(R.id.call_button) as android.view.ViewGroup).getChildAt(0).alpha =
            ((0.35f - r) / 0.35f).coerceIn(0f, 1f)
    }

    /**
     * animations.wakeUp: 0-800 ms the dusk scene fades to day (background, photo tint, badge,
     * texts; the plaque blends into the call button under it) while the bird opens an eye; then
     * until 1400 ms it stretches with a 20 px hop and chirps once if the setting is on; then
     * normal home. With animations off it is a 300 ms cross-fade (the chirp still plays).
     * The call button stays disabled until the end.
     */
    private fun wakeUp(to: HomeScreen, freezeAt: Int = -1) {
        val from = screen
        show(to)                    // the day scene underneath, in its final look ...
        stopAnimations()
        visible(true, R.id.bg_rest, R.id.photo_tint, R.id.rest_plaque, R.id.rest_title, R.id.rest_subtitle) // ... with the dusk scene over it
        updateBadge(from)
        findViewById<View>(R.id.call_button).isEnabled = false
        val bird = findViewById<BirdView>(R.id.bird)
        bird.play("peek")
        val motion = motionEnabled
        val fadeMs = if (motion) WAKE_FADE_MS else REDUCED_FADE_MS
        val d = resources.displayMetrics.density
        var stretched = false
        val note = findViewById<ImageView>(R.id.rest_note)
        val frame = { ms: Long ->
            val r = 1f - (ms / fadeMs.toFloat()).coerceIn(0f, 1f)
            // After the badge has switched to the day's one (a missed call's letter), leave it shown.
            setRestAmount(r, R.id.missed_title, R.id.missed_subtitle, badge = !stretched)
            setButtonBlend(r)
            if (motion && ms >= WAKE_FADE_MS) {
                if (!stretched) {
                    stretched = true
                    updateBadge(to); findViewById<View>(R.id.photo_badge).alpha = 1f
                    bird.play("stretch")
                    if (freezeAt < 0 && Settings.chirp) Feedback.chirp()
                    // The chirp's note (wakeup_sequence.png, frame 3), in the day's blue.
                    note.imageTintList = getColorStateList(R.color.gp_bird_blue)
                    note.visibility = View.VISIBLE
                }
                val u = (ms - WAKE_FADE_MS) / (WAKE_MS - WAKE_FADE_MS).toFloat()
                bird.translationY = -20f * d * kotlin.math.sin(Math.PI * u).toFloat()
                note.translationX = -(26f + 20f * u) * d
                note.translationY = -(56f + 45f * u) * d
                note.alpha = if (u < 0.2f) u / 0.2f else 1f - (u - 0.2f) / 0.8f
            }
        }
        if (freezeAt >= 0) { frame(freezeAt.toLong()); return }
        if (!motion && Settings.chirp) Feedback.chirp()
        transition = Timeline(if (motion) WAKE_MS else REDUCED_FADE_MS, frame) { show(to) }.start()
    }

    /**
     * Closing time: a quiet 1 s fade from the day scene to resting, no sound (300 ms with
     * animations off). The call button is disabled from the first frame.
     */
    private fun closeDown(to: HomeScreen, freezeAt: Int = -1) {
        val from = screen
        show(to)                    // the dusk scene in its final look ...
        stopAnimations()
        visible(true, R.id.call_button) // ... over the day scene, which fades away under it
        findViewById<View>(R.id.call_button).isEnabled = false
        val oldTexts = when (from) {
            HomeScreen.FAILED -> intArrayOf(R.id.fail_title, R.id.fail_subtitle)
            HomeScreen.MISSED -> intArrayOf(R.id.missed_title, R.id.missed_subtitle)
            else -> intArrayOf()
        }
        visible(true, *oldTexts)
        val bird = findViewById<BirdView>(R.id.bird)
        bird.play(when (from) { HomeScreen.FAILED -> "point"; HomeScreen.MISSED -> "letter"; else -> "idle" })
        bird.stop() // keep the frame, without its loop
        val totalMs = if (motionEnabled) CLOSE_MS else REDUCED_FADE_MS
        var lying = false
        val frame = { ms: Long ->
            val r = (ms / totalMs.toFloat()).coerceIn(0f, 1f)
            setRestAmount(r, *oldTexts)
            setButtonBlend(r)
            // The bird fades out standing and back in lying down.
            if (r >= 0.5f && !lying) { lying = true; bird.setImageResource(R.drawable.bird_lying) }
            bird.alpha = kotlin.math.abs(1f - 2f * r)
        }
        if (freezeAt >= 0) { frame(freezeAt.toLong()); return }
        transition = Timeline(totalMs, frame) { show(to) }.start()
    }

    /**
     * animations.tapReaction, while resting or paused: the plaque face goes down 8 px, the bird
     * opens one eye for 1.2 s, one note floats from its beak and a soft chime plays. Further taps
     * are ignored (no press, no sound) for 3 s. Nothing here can place a call.
     */
    private fun tapReaction(view: View) {
        if (!screen.resting || transition != null || preview == "tapped") return
        val now = SystemClock.uptimeMillis()
        if (now < tapLockedUntil) return
        tapLockedUntil = now + TAP_LOCKOUT_MS
        Feedback.chime(view)
        val bird = findViewById<BirdView>(R.id.bird)
        bird.play("peek")
        val motion = motionEnabled
        tapTimeline = Timeline(TAP_PEEK_MS, { ms ->
            if (motion) {
                // Down in 80 ms, held, back up between 300 and 450 ms.
                val press = when {
                    ms < 80 -> ms / 80f
                    ms < 300 -> 1f
                    ms < 450 -> 1f - (ms - 300) / 150f
                    else -> 0f
                }
                val u = ms / TAP_PEEK_MS.toFloat()
                tapPose(press, u, if (u < 0.2f) u / 0.2f else 1f - (u - 0.2f) / 0.8f)
            } else {
                // Animations off: static frames only.
                tapPose(if (ms < 300) 1f else 0f, 0.5f, 1f)
            }
        }) {
            tapTimeline = null
            tapPose(0f, 0f, 0f)
            visible(false, R.id.rest_note)
            if (resumed && screen.resting) bird.play("breathe")
        }.start()
    }

    /** One frame of the tap reaction: the plaque pressed by [pressed] (the bird lies on its face) and the note [note] of its way up. */
    private fun tapPose(pressed: Float, note: Float, noteAlpha: Float) {
        val d = resources.displayMetrics.density
        findViewById<RestPlaqueView>(R.id.rest_plaque).press = pressed
        val bird = findViewById<BirdView>(R.id.bird)
        if (preview == "tapped") { bird.stop(); bird.setImageResource(R.drawable.bird_lying_peek) }
        bird.translationY = 8f * d * pressed
        // As the incoming notes: 40 px left and 85 px up while fading.
        findViewById<View>(R.id.rest_note).apply {
            visibility = View.VISIBLE
            translationX = -40f * d * note
            translationY = -85f * d * note
            alpha = noteAlpha
        }
    }

    private fun startAnimations() {
        stopAnimations()
        if (transition != null) return
        val bird = findViewById<BirdView>(R.id.bird)
        when (screen) {
            HomeScreen.HOME -> bird.play("nudge")
            HomeScreen.MISSED -> bird.play("letter")
            HomeScreen.FAILED -> bird.play("point")
            HomeScreen.RESTING, HomeScreen.PAUSED -> bird.play("breathe")
            HomeScreen.OFFLINE -> findViewById<BirdView>(R.id.bird_offline).play("sleep")
        }
        if (screen.canDial) findViewById<GlowView>(R.id.glow).start(GLOW_SLOW_MS)
        if (preview == "tapped") tapPose(pressed = 1f, note = 0.75f, noteAlpha = 1f)
    }

    private fun stopAnimations() {
        anims.stopAll()
        findViewById<GlowView>(R.id.glow).stop()
        findViewById<BirdView>(R.id.bird).stop()
        findViewById<BirdView>(R.id.bird_offline).stop()
    }

    private fun visible(show: Boolean, vararg ids: Int) {
        for (id in ids) findViewById<View>(id).visibility = if (show) View.VISIBLE else View.GONE
    }

    /** Screen pinning, so swipes can't leave the app. Off by default: see KIOSK in abufon.properties. */
    private fun startKiosk() {
        val am = getSystemService(android.app.ActivityManager::class.java)
        if (am.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE) return
        try { startLockTask() } catch (e: Exception) { android.util.Log.w("Abufon", "Lock task failed", e) }
    }

    private fun hasPermissions() = PERMISSIONS.all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissionsIfNeeded() {
        if (!hasPermissions()) requestPermissions(PERMISSIONS, 1)
    }

    private val listener = object : CoreListenerStub() {
        override fun onAccountRegistrationStateChanged(
            core: Core, account: Account, state: RegistrationState, message: String
        ) = onRegistration(state)
    }

    companion object {
        val PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        const val EXTRA_FAILED = "failed"
        const val EXTRA_PREVIEW = "preview"
        private const val KEY_STATE = "state"
        private const val KEY_PREVIEW = "preview"
        private const val KEY_PREVIEW_AT = "preview_at"
        private const val KEY_OFFLINE = "offline"
        private const val KEY_FAILED = "failed"
        private const val OFFLINE_DELAY_MS = 5_000L
        private const val SCHEDULE_TICK_MS = 30_000L
        // Corner hold (states.parent.lockHold): the ring appears after 1 s and fills over 2 s.
        private const val LOCK_APPEARS_MS = 1_000L
        private const val LOCK_FILLS_MS = 2_000L
        // animations.tapReaction
        private const val TAP_PEEK_MS = 1_200L
        private const val TAP_LOCKOUT_MS = 3_000L
        // animations.wakeUp: 0-800 ms cross-fade, 800-1400 ms stretch, hop and chirp; closing 1 s.
        private const val WAKE_FADE_MS = 800L
        private const val WAKE_MS = 1_400L
        private const val CLOSE_MS = 1_000L
        private const val REDUCED_FADE_MS = 300L
        const val GLOW_SLOW_MS = 2_200L
        const val GLOW_FAST_MS = 1_200L

        fun open(context: Context, failed: Boolean = false) {
            context.startActivity(
                Intent(context, HomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(EXTRA_FAILED, failed)
            )
        }
    }
}
