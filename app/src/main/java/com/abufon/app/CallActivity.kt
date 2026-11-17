package com.abufon.app

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.linphone.core.Call
import org.linphone.core.CallListenerStub
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.MediaDirection
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.roundToInt

/**
 * CALLING — granny's photo with ripples, flapping bird, "Llamando a …", hold-to-hang-up
 * IN CALL — hold-to-hang-up and the self-view over either
 *             · granny's video, once frames are really arriving, or
 *             · the v4 "no video" look while there is no picture (still waiting for the first
 *               frame, camera off, audio-only, on hold, stalled): her photo (centred in landscape
 *               so the self-view never covers it), two rings that move with her voice, the hint
 *               "Esperando su video…" / "Su cámara está apagada" and, unless we are still waiting
 *               for the first picture, the camera-off badge.
 * Hanging up needs a 1.5 s hold; the ring fills while held.
 */
class CallActivity : Activity() {
    private enum class Screen { CALLING, IN_CALL }

    private lateinit var videoContainer: FrameLayout
    private lateinit var remoteVideo: TextureView
    private val handler = Handler(Looper.getMainLooper())
    private val anims = Animations()
    private var shown: Screen? = null
    private var resumed = false
    private var videoW = 0
    private var videoH = 0

    /** The call whose frame-decoded callback we're listening to. */
    private var watchedCall: Call? = null
    private var videoShown = false
    private var quietPolls = 0

    private lateinit var voiceRings: VoiceRingsView
    /** Her video was showing and went away (stalled, or the call was put on hold). */
    private var videoStopped = false
    /** What the hint and badge currently say; null while the no-video look is not up. */
    private var saysCameraOff: Boolean? = null
    private var levelPolling = false

    /**
     * Debug builds only: `--es preview <name>` shows the screen without a real call (start it
     * through HomeActivity, with `--es orient land|port`):
     *  calling — ringing out;  incall — video showing (black, there is no video);
     *  waiting (or novideo) — answered, no picture yet;  dropped — her video stopped;
     *  arrives — waiting, then the video fades in after 3 s;
     *  answered — calling, then she answers after 3 s (the photo slides to the centre in landscape).
     * In waiting/dropped the rings follow a made-up voice; `--ei level 0..100` holds them still.
     */
    private val preview: String?
        get() = if (BuildConfig.DEBUG) intent.getStringExtra(EXTRA_PREVIEW) else null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        setContentView(R.layout.screen_call)
        if (BuildConfig.DEBUG) debugOrientation(intent.getStringExtra("orient"))
        goImmersive(keepScreenOn = true)

        videoContainer = findViewById(R.id.video_container)
        remoteVideo = findViewById(R.id.remote_video)
        // The container stays laid out for the whole call (so the renderer keeps its surface)
        // and is revealed by alpha once a frame has been decoded.
        videoContainer.visibility = View.VISIBLE
        videoContainer.alpha = 0f
        voiceRings = findViewById(R.id.voice_rings)
        videoStopped = savedInstanceState?.getBoolean(KEY_VIDEO_STOPPED) ?: false
        findViewById<View>(R.id.hangup_button).holdToConfirm(
            findViewById(R.id.hangup_ring),
            onShortTap = { pulse(findViewById(R.id.hangup_hint)) }
        ) {
            if (preview == null) LinphoneManager.hangUp()
            HomeActivity.open(this@CallActivity)
            finish()
        }

        val core = LinphoneManager.coreOrNull ?: run { finish(); return }
        core.setNativeVideoWindowId(remoteVideo)
        core.setNativePreviewWindowId(findViewById<SelfView>(R.id.self_view))
        // Re-run the top-biased crop whenever the container is laid out (e.g. after rotation).
        videoContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitVideo() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_VIDEO_STOPPED, videoStopped)
    }

    override fun onResume() {
        super.onResume()
        goImmersive(keepScreenOn = true)
        resumed = true
        shown = null
        val core = LinphoneManager.coreOrNull ?: run { finish(); return }
        updateRotation() // before anything starts the camera, previews included
        when (preview) {
            "calling" -> { showCalling(null); return }
            "answered" -> {
                videoStopped = false
                showCalling(null)
                handler.postDelayed(previewAnswered, 3_000)
                return
            }
            "incall" -> { showInCall(); setVideoShown(true); core.setVideoPreviewEnabled(true); return }
            "waiting", "novideo", "arrives" -> {
                videoStopped = false
                showInCall(); core.setVideoPreviewEnabled(true)
                if (preview == "arrives") handler.postDelayed(previewArrives, 3_000)
                return
            }
            "dropped" -> { videoStopped = true; showInCall(); core.setVideoPreviewEnabled(true); return }
        }
        val call = LinphoneManager.activeCall()
        if (call == null) { leave(); return }
        core.addListener(listener)
        watch(call)
        onState(call, call.state)
        handler.post(poll)
    }

    override fun onPause() {
        resumed = false
        stopAnimations()
        val core = LinphoneManager.coreOrNull
        if (preview != null) core?.setVideoPreviewEnabled(false)
        core?.removeListener(listener)
        watchedCall?.removeListener(callListener)
        watchedCall = null
        handler.removeCallbacks(poll)
        handler.removeCallbacks(previewArrives)
        handler.removeCallbacks(previewAnswered)
        // Settle the two short transitions: the photo sliding to the centre and the video fading in.
        findViewById<View>(R.id.photo_group).animate().cancel()
        videoContainer.animate().cancel()
        if (videoShown) {
            videoContainer.alpha = 1f
            findViewById<View>(R.id.photo_group).visibility = View.GONE
            hideNoVideo()
        }
        super.onPause()
    }

    override fun onDestroy() {
        // Only detach the views if they are still ours: after a rotation or a quick re-open, a
        // newer instance may already have attached its own.
        LinphoneManager.coreOrNull?.let { core ->
            if (::remoteVideo.isInitialized && core.nativeVideoWindowId === remoteVideo) core.setNativeVideoWindowId(null)
            if (core.nativePreviewWindowId === findViewById<View?>(R.id.self_view)) core.setNativePreviewWindowId(null)
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = Unit

    private fun onState(call: Call, state: Call.State) {
        when (state) {
            Call.State.StreamsRunning -> {
                showInCall()
                // Wait for a real frame before swapping the photo for the video.
                if (!videoShown) call.requestNotifyNextVideoFrameDecoded()
                updateNoVideo() // now we know whether she sends a picture at all
            }
            Call.State.Connected, Call.State.UpdatedByRemote, Call.State.Updating, Call.State.Resuming -> showInCall()
            // On hold (either side): there is no picture, so go back to her photo.
            Call.State.Paused, Call.State.PausedByRemote, Call.State.Pausing -> {
                videoStopped = true
                showInCall()
                setVideoShown(false)
                updateNoVideo()
            }
            Call.State.End, Call.State.Error, Call.State.Released -> {
                if (LinphoneManager.activeCall() == null) leave()
            }
            else -> showCalling(call)
        }
    }

    private fun showCalling(call: Call?) {
        val incoming = call?.dir == Call.Dir.Incoming
        findViewById<TextView>(R.id.calling_label).text =
            if (incoming) getString(R.string.connecting_label)
            else getString(R.string.calling_label, BuildConfig.GRANNY_NAME)
        if (shown == Screen.CALLING) return
        shown = Screen.CALLING
        videoStopped = false
        setVideoShown(false)
        setVisible(true, R.id.photo_group, R.id.calling_label, R.id.bird)
        setVisible(false, R.id.self_frame)
        stopAnimations()
        hideNoVideo()
        movePhoto(inCall = false, animate = false)
        if (resumed) {
            findViewById<BirdView>(R.id.bird).play("flap")
            val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            anims.ripples(if (portrait) 1.28f else 1.22f, findViewById(R.id.ripple1), findViewById(R.id.ripple2))
        }
    }

    private fun showInCall() {
        if (shown == Screen.IN_CALL) return
        val fromCalling = shown == Screen.CALLING
        shown = Screen.IN_CALL
        stopAnimations()
        setVisible(false, R.id.calling_label, R.id.bird, R.id.ripple1, R.id.ripple2)
        setVisible(true, R.id.self_frame)
        // Granny's photo stays until setVideoShown(true).
        findViewById<View>(R.id.photo_group).visibility = if (videoShown) View.GONE else View.VISIBLE
        // She answered while we were watching: the photo slides to its in-call place. On a fresh
        // start or after a rotation it is simply there.
        movePhoto(inCall = true, animate = fromCalling && resumed)
        updateNoVideo()
    }

    /**
     * In landscape the in-call photo is centred (v4 incallNoVideo.photoFrame) so the self-view never
     * covers it; while calling it keeps its place on the left. The layout has the photo at its
     * calling position and the voice rings at the in-call one, so the photo is moved by the
     * distance between the two centres (0 in portrait).
     */
    private fun movePhoto(inCall: Boolean, animate: Boolean) {
        val photo = findViewById<View>(R.id.photo_group)
        val from = photo.layoutParams as FrameLayout.LayoutParams
        val to = voiceRings.layoutParams as FrameLayout.LayoutParams
        val dx = if (inCall) (to.leftMargin + to.width / 2f) - (from.leftMargin + from.width / 2f) else 0f
        val dy = if (inCall) (to.topMargin + to.height / 2f) - (from.topMargin + from.height / 2f) else 0f
        photo.animate().cancel()
        if (animate && motionEnabled && (photo.translationX != dx || photo.translationY != dy)) {
            photo.animate().translationX(dx).translationY(dy).setDuration(250).start()
        } else {
            photo.translationX = dx
            photo.translationY = dy
        }
    }

    /** She is not sending a picture at all: an audio-only call, or her side of the video is off. */
    private fun noCamera(call: Call?): Boolean {
        if (call == null || call.state != Call.State.StreamsRunning) return false
        val params = call.currentParams
        if (!params.isVideoEnabled) return true
        val direction = params.videoDirection
        return direction == MediaDirection.SendOnly || direction == MediaDirection.Inactive
    }

    /**
     * The v4 "no video" look, on whenever we are in the call and her video is not showing: rings,
     * hint and, once her video has stopped or she has no camera, the badge. Safe to call repeatedly.
     */
    private fun updateNoVideo() {
        if (shown != Screen.IN_CALL || videoShown) return
        val cameraOff = videoStopped || (preview == null && noCamera(LinphoneManager.activeCall()))
        if (saysCameraOff != cameraOff) {
            saysCameraOff = cameraOff
            findViewById<TextView>(R.id.video_hint).setText(if (cameraOff) R.string.camera_off else R.string.waiting_video)
            setVisible(cameraOff, R.id.camera_badge)
            setVisible(true, R.id.video_hint, R.id.voice_rings)
        }
        if (resumed && !levelPolling && motionEnabled) {
            levelPolling = true
            voiceRings.start()
            handler.post(levelPoll)
        }
    }

    /** Takes the rings, hint and badge away (video is showing, or we are back to calling). */
    private fun hideNoVideo() {
        stopVoice()
        saysCameraOff = null
        setVisible(false, R.id.voice_rings, R.id.video_hint, R.id.camera_badge)
    }

    private fun stopVoice() {
        levelPolling = false
        handler.removeCallbacks(levelPoll)
        voiceRings.stop()
    }

    /** 20 times a second: how loud granny is right now. The rings view does the smoothing. */
    private val levelPoll = object : Runnable {
        override fun run() {
            if (!levelPolling) return
            voiceRings.level = if (preview != null) previewLevel()
            else LinphoneManager.activeCall()?.let { VoiceLevel.fromDb(it.playVolume) } ?: 0f
            handler.postDelayed(this, LEVEL_POLL_MS)
        }
    }

    /** Debug previews: a made-up voice (bursts of syllables with pauses), or a fixed `--ei level`. */
    private fun previewLevel(): Float {
        val fixed = intent.getIntExtra("level", -1)
        if (fixed >= 0) return fixed / 100f
        val t = android.os.SystemClock.uptimeMillis() / 1000.0
        val phrase = max(0.0, sin(t * 2 * PI / 2.6))
        val syllable = 0.55 + 0.45 * sin(t * 2 * PI * 3.3)
        return (phrase * syllable).toFloat()
    }

    private val previewArrives = Runnable { setVideoShown(true) }
    private val previewAnswered = Runnable {
        showInCall()
        // Start the camera once the self-view is laid out, as in a real call.
        handler.post {
            if (resumed) LinphoneManager.coreOrNull?.let { core ->
                core.setNativePreviewWindowId(findViewById<SelfView>(R.id.self_view))
                core.setVideoPreviewEnabled(true)
            }
        }
    }

    /** Swap between granny's live video and her photo. Never shows an empty black screen. */
    private fun setVideoShown(show: Boolean) {
        if (videoShown == show) return
        videoShown = show
        quietPolls = 0
        videoContainer.animate().cancel()
        if (show) {
            videoStopped = false // her picture is back; only a new drop shows "camera off" again
            // v4 "videoArrives": the video fades in above the photo, then the photo, rings, hint
            // and badge are removed.
            videoContainer.animate().alpha(1f).setDuration(250)
                .withEndAction {
                    if (videoShown) {
                        findViewById<View>(R.id.photo_group).visibility = View.GONE
                        hideNoVideo()
                    }
                }
                .start()
        } else {
            videoContainer.alpha = 0f
            if (shown != null) findViewById<View>(R.id.photo_group).visibility = View.VISIBLE
            if (shown == Screen.IN_CALL) {
                videoStopped = true
                updateNoVideo()
            }
            // Ask to be told when the picture comes back.
            if (shown == Screen.IN_CALL) watchedCall?.requestNotifyNextVideoFrameDecoded()
        }
    }

    private fun watch(call: Call) {
        if (watchedCall === call) return
        watchedCall?.removeListener(callListener)
        watchedCall = call
        call.addListener(callListener)
    }

    private val callListener = object : CallListenerStub() {
        override fun onNextVideoFrameDecoded(call: Call) {
            if (shown == Screen.IN_CALL && call.state == Call.State.StreamsRunning) {
                readVideoSize(call)
                setVideoShown(true)
            }
        }
    }

    private fun stopAnimations() {
        stopVoice()
        anims.stopAll()
        findViewById<BirdView>(R.id.bird).stop()
        for (id in intArrayOf(R.id.ripple1, R.id.ripple2)) findViewById<View>(id).visibility = View.GONE
    }

    private fun setVisible(visible: Boolean, vararg ids: Int) {
        for (id in ids) findViewById<View>(id).visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * Once a second: track the received video size (it can change when granny rotates her phone)
     * and notice when the picture stops arriving, so the frozen last frame is replaced by her photo.
     */
    private val poll = object : Runnable {
        override fun run() {
            val call = LinphoneManager.activeCall()
            if (call != null) {
                watch(call)
                readVideoSize(call)
                if (videoShown) {
                    val hasVideo = call.currentParams.isVideoEnabled
                    val kbps = call.videoStats?.downloadBandwidth ?: 0f
                    quietPolls = if (!hasVideo || kbps < 1f) quietPolls + 1 else 0
                    if (!hasVideo || quietPolls >= STALL_POLLS) setVideoShown(false)
                } else if (shown == Screen.IN_CALL && call.state == Call.State.StreamsRunning) {
                    call.requestNotifyNextVideoFrameDecoded()
                    updateNoVideo() // she may have turned her camera off or on meanwhile
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun readVideoSize(call: Call) {
        val def = call.currentParams.receivedVideoDefinition
        val w = def?.width ?: 0
        val h = def?.height ?: 0
        if (w > 0 && h > 0 && (w != videoW || h != videoH)) {
            videoW = w; videoH = h
            fitVideo()
        }
    }

    /**
     * Cover the screen without stretching: size the video view to the video's own aspect ratio,
     * scaled up until it fills the screen, centred horizontally and biased towards the top so
     * granny's face stays in view. Before the size is known the view just fills the screen.
     */
    private fun fitVideo() {
        val cw = videoContainer.width
        val ch = videoContainer.height
        if (cw == 0 || ch == 0) return
        val lp = remoteVideo.layoutParams as FrameLayout.LayoutParams
        if (videoW == 0 || videoH == 0) {
            if (lp.width != FrameLayout.LayoutParams.MATCH_PARENT) {
                remoteVideo.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            return
        }
        val scale = max(cw.toFloat() / videoW, ch.toFloat() / videoH)
        val w = (videoW * scale).roundToInt()
        val h = (videoH * scale).roundToInt()
        val left = (cw - w) / 2
        val top = ((ch - h) * VIDEO_VERTICAL_BIAS).roundToInt()
        if (lp.width == w && lp.height == h && lp.leftMargin == left && lp.topMargin == top) return
        remoteVideo.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.START).apply {
            leftMargin = left
            topMargin = top
        }
    }

    private fun updateRotation() {
        @Suppress("DEPRECATION")
        val degrees = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        LinphoneManager.coreOrNull?.setDeviceRotation(degrees)
    }

    private fun leave() {
        HomeActivity.open(this, failed = LinphoneManager.lastCallFailed)
        finish()
    }

    private val listener = object : CoreListenerStub() {
        override fun onCallStateChanged(core: Core, call: Call, state: Call.State, message: String) =
            onState(call, state)
    }

    companion object {
        const val EXTRA_PREVIEW = "preview"
        /** 0 = keep the top of the video, 0.5 = centre crop. */
        private const val VIDEO_VERTICAL_BIAS = 0.25f
        /** Seconds without incoming video before the frozen frame gives way to the photo. */
        private const val STALL_POLLS = 3
        private const val LEVEL_POLL_MS = 50L
        private const val KEY_VIDEO_STOPPED = "video_stopped"
    }
}
