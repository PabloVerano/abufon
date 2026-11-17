package com.abufon.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.util.Log
import org.linphone.core.Address
import org.linphone.core.AudioDevice
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.LogLevel
import org.linphone.core.MediaEncryption
import org.linphone.core.Reason
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType

/**
 * Owns the single Linphone Core for the whole process. Created from [AbufonApp.onCreate],
 * so it exists whenever any activity, the service or the boot receiver runs.
 */
@SuppressLint("StaticFieldLeak")
object LinphoneManager {
    private const val TAG = "Abufon"

    private lateinit var appContext: Context
    lateinit var core: Core
        private set

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    // Outcome tracking for the current call, so the UI can show "No contestó".
    private var callConnected = false
    private var userHungUp = false
    /** True when the last outgoing call ended without granny ever answering. */
    var lastCallFailed = false
        private set
    /**
     * Call-ID of granny's call that is ringing here and has not been answered or declined by
     * hand yet. If that call ends in this state (it rang out or she gave up), it is a missed call.
     */
    /** Granny's call that is ringing here and nobody has answered yet (compared by identity). */
    private var ringingUnanswered: Call? = null
    private var ringingSince = 0L
    private val callTimeout = Runnable {
        val call = activeCall() ?: return@Runnable
        if (call.dir == Call.Dir.Outgoing && !callConnected) {
            Log.i(TAG, "No answer after ${BuildConfig.CALL_TIMEOUT_SECONDS}s, giving up")
            call.terminate()
        }
    }

    /** True once the core has been created and started. False in safe mode or if start-up failed. */
    var isReady = false
        private set

    val coreOrNull: Core? get() = if (isReady) core else null

    /**
     * Starts the core. Never throws: Abufon is the HOME app, so a failure here must leave a
     * running launcher (showing the offline screen) instead of a crash loop. Returns [isReady].
     */
    fun tryInit(context: Context): Boolean {
        if (isReady) return true
        return try {
            init(context)
            isReady = true
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Linphone start-up failed", t)
            // Don't leave a half-started core behind; the next attempt creates a fresh one.
            if (::core.isInitialized) try { core.removeListener(listener); core.stop() } catch (_: Throwable) {}
            false
        }
    }

    private fun init(context: Context) {
        appContext = context.applicationContext

        val factory = Factory.instance()
        factory.setDebugMode(BuildConfig.DEBUG, TAG)
        factory.loggingService.setLogLevel(if (BuildConfig.DEBUG) LogLevel.Message else LogLevel.Warning)

        core = factory.createCore(null, null, appContext)
        configureCore(factory)
        configureAccount(factory)
        core.addListener(listener)
        core.start()
        core.setConsolidatedPresence(org.linphone.core.ConsolidatedPresence.Online)
        selectFrontCamera()
        Log.i(TAG, "Linphone core started")
    }

    private fun configureCore(factory: Factory) {
        core.setUserAgent("Abufon", BuildConfig.VERSION_NAME)
        core.setAutoIterateEnabled(true)
        core.setKeepAliveEnabled(true)

        // We never want the core's own ringer: non-granny calls must be declined silently,
        // and granny's ringtone is played by us.
        core.setRing(null)
        core.setNativeRingingEnabled(false)
        core.setRingDuringIncomingEarlyMedia(false)

        core.setEchoCancellationEnabled(true)
        core.setMicEnabled(true)
        core.setMaxCalls(1)
        // By default the SDK pauses every call when another sound takes audio focus, and nothing
        // here would resume it. This device has one job, so keep the call running instead.
        core.config.setBool("audio", "android_pause_calls_when_audio_focus_lost", false)
        // The Linphone phone apps refuse unencrypted calls (488 Not acceptable here), so audio
        // and video are always encrypted. MEDIA_ENCRYPTION picks how: srtp (default) or zrtp.
        val wanted = if (BuildConfig.MEDIA_ENCRYPTION.equals("zrtp", ignoreCase = true)) MediaEncryption.ZRTP else MediaEncryption.SRTP
        val encryption = if (core.isMediaEncryptionSupported(wanted)) wanted else MediaEncryption.SRTP
        core.setMediaEncryption(encryption)
        core.setMediaEncryptionMandatory(true)
        Log.i(TAG, "Media encryption: $encryption")

        core.setVideoCaptureEnabled(true)
        core.setVideoDisplayEnabled(true)
        val policy = factory.createVideoActivationPolicy()
        policy.setAutomaticallyAccept(true)
        policy.setAutomaticallyInitiate(true)
        core.setVideoActivationPolicy(policy)

        // NAT traversal so video works behind home routers.
        val nat = core.createNatPolicy()
        nat.setStunServer("stun.linphone.org")
        nat.setStunEnabled(true)
        nat.setIceEnabled(true)
        core.setNatPolicy(nat)
    }

    private fun configureAccount(factory: Factory) {
        core.clearAccounts()
        core.clearAllAuthInfo()

        val identity = factory.createAddress(BuildConfig.PORTAL_SIP)
        if (identity == null) {
            Log.e(TAG, "Invalid PORTAL_SIP: ${BuildConfig.PORTAL_SIP}")
            return
        }
        val auth = factory.createAuthInfo(
            identity.username ?: "", null, BuildConfig.PORTAL_PASSWORD, null, null, identity.domain
        )
        core.addAuthInfo(auth)

        val params = core.createAccountParams()
        params.setIdentityAddress(identity)
        val server = factory.createAddress("sip:${identity.domain}")!!
        server.setTransport(TransportType.Tls)
        params.setServerAddress(server)
        params.setRegisterEnabled(true)
        // Publish presence so granny's Linphone shows the Portal as online, not unknown.
        params.setPublishEnabled(true)
        params.setNatPolicy(core.natPolicy)

        val account = core.createAccount(params)
        core.addAccount(account)
        core.setDefaultAccount(account)
    }

    private fun selectFrontCamera() {
        core.reloadVideoDevices() // camera permission may have been granted after the core started
        val devices = core.videoDevicesList
        Log.i(TAG, "Video devices: ${devices.joinToString()}")
        val front = devices.firstOrNull { it.contains("front", ignoreCase = true) }
            ?: devices.firstOrNull { !it.contains("StaticImage", ignoreCase = true) }
        if (front != null) core.setVideoDevice(front)
        // Note: the Portal gives apps a virtual camera (id 0) that its AI service crops and pans
        // to follow people; its native size is 1280x720 (16:9) while Linphone asks for 640x480
        // (4:3). Asking for 720p is a candidate fix for the self-view changing shape, not yet
        // verified, so Linphone's default stays for now.
    }

    val registrationState: RegistrationState
        get() = coreOrNull?.defaultAccount?.state ?: RegistrationState.None

    /**
     * The one call this app can have, whatever its state. `core.currentCall` is null for a paused
     * call and `core.callsNb` still counts a call that has ended but isn't released yet, so every
     * "is there a call?" question goes through here to get the same answer.
     */
    fun activeCall(): Call? {
        val core = coreOrNull ?: return null
        return core.calls.firstOrNull {
            it.state != Call.State.End && it.state != Call.State.Error && it.state != Call.State.Released
        }
    }

    fun isGranny(address: Address?): Boolean {
        if (address == null) return false
        val granny = Factory.instance().createAddress(BuildConfig.GRANNY_SIP) ?: return false
        return address.username.equals(granny.username, ignoreCase = true) &&
            address.domain.equals(granny.domain, ignoreCase = true)
    }

    fun callGranny(): Boolean {
        if (!isReady || !Settings.canCallNow()) return false
        if (activeCall() != null) return true
        val address = core.createAddress(BuildConfig.GRANNY_SIP) ?: return false
        selectFrontCamera()
        val params = core.createCallParams(null) ?: return false
        params.setVideoEnabled(true)
        params.setCameraEnabled(true)
        speaker()?.let { params.setOutputAudioDevice(it) }
        CallRecorder.prepare(params)
        val call = core.inviteAddressWithParams(address, params) ?: return false
        startTracking()
        handler.postDelayed(callTimeout, BuildConfig.CALL_TIMEOUT_SECONDS * 1000L)
        return true
    }

    private fun startTracking() {
        callConnected = false
        userHungUp = false
        lastCallFailed = false
    }

    /** The current granny call that is still ringing, if any. */
    fun ringingCall(): Call? = coreOrNull?.calls?.firstOrNull {
        it.state == Call.State.IncomingReceived || it.state == Call.State.IncomingEarlyMedia
    }

    fun answer(call: Call) {
        ringingUnanswered = null
        stopRinging()
        selectFrontCamera()
        val params = core.createCallParams(call) ?: return
        params.setVideoEnabled(true)
        params.setCameraEnabled(true)
        speaker()?.let { params.setOutputAudioDevice(it) }
        CallRecorder.prepare(params)
        startTracking()
        call.acceptWithParams(params)
    }

    fun decline(call: Call) {
        ringingUnanswered = null // declined by hand here: not a missed call
        stopRinging()
        call.decline(Reason.Declined)
    }

    fun hangUp() {
        userHungUp = true
        ringingUnanswered = null // ended by hand here: not a missed call
        stopRinging()
        core.terminateAllCalls()
    }

    private fun speaker(): AudioDevice? = core.audioDevices.firstOrNull {
        it.type == AudioDevice.Type.Speaker && it.hasCapability(AudioDevice.Capabilities.CapabilityPlay)
    }

    private fun routeToSpeaker(call: Call) {
        speaker()?.let { call.setOutputAudioDevice(it) }
    }

    private val listener = object : CoreListenerStub() {
        override fun onCallStateChanged(core: Core, call: Call, state: Call.State, message: String) {
            Log.i(TAG, "Call ${call.remoteAddress.asStringUriOnly()} -> $state")
            when (state) {
                Call.State.IncomingReceived -> onIncoming(call)
                Call.State.Connected, Call.State.StreamsRunning -> {
                    callConnected = true
                    ringingUnanswered = null
                    // A call with granny that connects, in either direction, clears the missed-call cue.
                    if (isGranny(call.remoteAddress)) MissedCall.clear()
                    handler.removeCallbacks(callTimeout)
                    stopRinging()
                    routeToSpeaker(call)
                    call.setEchoCancellationEnabled(true)
                    if (state == Call.State.StreamsRunning) CallRecorder.start(call)
                }
                Call.State.Paused -> {
                    // Should not happen (see configureCore), but never leave granny on hold.
                    handler.postDelayed({ if (call.state == Call.State.Paused) call.resume() }, 500)
                }
                Call.State.End, Call.State.Error -> {
                    handler.removeCallbacks(callTimeout)
                    if (call.dir == Call.Dir.Outgoing && isGranny(call.remoteAddress)) {
                        lastCallFailed = !callConnected && !userHungUp
                    }
                    if (ringingUnanswered != null && ringingUnanswered === call) {
                        // Granny's call rang here and ended without anyone answering it.
                        ringingUnanswered = null
                        MissedCall.record(ringingSince)
                    }
                    stopRinging()
                }
                Call.State.Released -> {
                    stopRinging()
                    IncomingNotification.cancel(appContext)
                }
                else -> Unit
            }
            CallLog.onCallState(call, state, message)
        }

        override fun onAccountRegistrationStateChanged(
            core: Core, account: org.linphone.core.Account, state: RegistrationState, message: String
        ) {
            Log.i(TAG, "Registration -> $state ($message)")
            CallLog.event("registration -> $state ($message)")
        }
    }

    private fun onIncoming(call: Call) {
        val remote = call.remoteAddress
        // Anyone but granny, or granny while already busy: decline without a sound.
        val busy = core.calls.any {
            it !== call && it.state != Call.State.End && it.state != Call.State.Error && it.state != Call.State.Released
        }
        if (!isGranny(remote) || busy) {
            Log.i(TAG, "Silently declining ${remote.asStringUriOnly()}")
            call.decline(Reason.Declined)
            return
        }
        // Outside the hours the parent allows (or while paused): granny is told we're busy and
        // nothing rings here.
        if (!Settings.acceptsIncomingNow()) {
            Log.i(TAG, "Granny called outside the allowed hours: declined without ringing")
            call.decline(Reason.Busy)
            MissedCall.record()
            return
        }
        ringingUnanswered = call
        ringingSince = System.currentTimeMillis()
        startRinging()
        // Full-screen notification is the reliable path from the background on Android 10;
        // the direct start works when we're foreground (we're the launcher) or have SYSTEM_ALERT_WINDOW.
        IncomingNotification.show(appContext)
        try {
            appContext.startActivity(
                Intent(appContext, IncomingCallActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Direct start of incoming screen failed", e)
        }
    }

    private fun startRinging() = Ringer.start(appContext)

    fun stopRinging() {
        Ringer.stop(appContext)
        IncomingNotification.cancel(appContext)
    }

    /** Debug builds only: ring and open the incoming screen as a real call would, without a call. */
    fun debugRing(delayMs: Long) {
        if (!BuildConfig.DEBUG || !isReady) return
        handler.postDelayed({
            startRinging()
            IncomingNotification.show(appContext)
            appContext.startActivity(
                Intent(appContext, IncomingCallActivity::class.java)
                    .putExtra(IncomingCallActivity.EXTRA_PREVIEW, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            )
            handler.postDelayed({ stopRinging() }, 12_000)
        }, delayMs)
    }
}
