package com.abufon.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.TextView
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub

/**
 * Granny is calling: same layout as home. The answer button and the photo both answer.
 * Declining needs a 1.5 s hold on the small button in the corner.
 */
class IncomingCallActivity : Activity() {
    private var countdown: CountDownTimer? = null
    private lateinit var label: TextView
    private val anims = Animations()

    /** Debug builds only: `--ez preview true` shows the screen without a real call. */
    private val preview get() = BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_PREVIEW, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        setContentView(R.layout.screen_incoming)
        if (BuildConfig.DEBUG) debugOrientation(intent.getStringExtra("orient"))
        goImmersive(keepScreenOn = true)

        label = findViewById(R.id.countdown)
        label.setText(R.string.incoming_tap)
        findViewById<View>(R.id.answer_button).setOnClickListener { answer() }
        findViewById<View>(R.id.photo_group).setOnClickListener { Feedback.tap(it); answer() }
        findViewById<View>(R.id.decline_button).holdToConfirm(
            findViewById(R.id.decline_ring),
            onShortTap = { pulse(findViewById(R.id.decline_hint)) }
        ) {
            if (!preview) LinphoneManager.ringingCall()?.let { LinphoneManager.decline(it) }
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        goImmersive(keepScreenOn = true)
        if (!preview && LinphoneManager.ringingCall() == null) { finish(); return }
        LinphoneManager.coreOrNull?.addListener(listener)
        startAnimations()
        if (Settings.autoAnswer) startCountdown()
    }

    override fun onPause() {
        LinphoneManager.coreOrNull?.removeListener(listener)
        countdown?.cancel()
        anims.stopAll()
        findViewById<View>(R.id.photo_group).rotation = 0f
        findViewById<GlowView>(R.id.glow).stop()
        findViewById<BirdView>(R.id.bird).stop()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = Unit

    private fun startAnimations() {
        findViewById<BirdView>(R.id.bird).play("sing_hop")
        findViewById<GlowView>(R.id.glow).start(HomeActivity.GLOW_FAST_MS)
        anims.wiggle(findViewById(R.id.photo_group))
        if (motionEnabled) anims.notes(findViewById(R.id.note1), findViewById(R.id.note2))
    }

    private fun startCountdown() {
        val total = Settings.autoAnswerSeconds * 1000L
        countdown?.cancel()
        countdown = object : CountDownTimer(total, 200) {
            override fun onTick(ms: Long) {
                label.text = getString(R.string.incoming_countdown, ((ms + 999) / 1000).toInt())
            }
            override fun onFinish() = answer()
        }.start()
    }

    private fun answer() {
        countdown?.cancel()
        if (preview) return
        val call = LinphoneManager.ringingCall() ?: run { finish(); return }
        LinphoneManager.answer(call)
        startActivity(Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private val listener = object : CoreListenerStub() {
        override fun onCallStateChanged(core: Core, call: Call, state: Call.State, message: String) {
            when (state) {
                Call.State.End, Call.State.Error, Call.State.Released ->
                    if (!preview && LinphoneManager.ringingCall() == null) finish()
                else -> Unit
            }
        }
    }

    companion object {
        const val EXTRA_PREVIEW = "preview"
    }
}

/** Short "look here" bounce for a hint chip after a tap that needed a hold. */
fun pulse(view: View) {
    view.animate().cancel()
    view.scaleX = 1f; view.scaleY = 1f
    view.animate().scaleX(1.12f).scaleY(1.12f).setDuration(140).withEndAction {
        view.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
    }.start()
}
