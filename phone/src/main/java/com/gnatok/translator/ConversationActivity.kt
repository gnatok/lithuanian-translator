package com.gnatok.translator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.gnatok.translator.core.PcmTurn
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.*
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Foreground conversation loop. Input stays armed between turns, but PCM does not.
 * Speech interruption during playback is deliberately not claimed: no AEC/barge-in yet.
 */
class ConversationActivity : ComponentActivity() {
    private lateinit var state: TextView
    private lateinit var result: TextView
    private lateinit var control: Button
    private lateinit var phoneStart: Button
    private lateinit var glasses: GlassesCapture
    private lateinit var playback: EnglishPlayback
    private lateinit var models: SpeechModels
    private lateinit var translator: Translator
    private var operation: Job? = null
    private var cleanup: Job? = null
    private var active = false
    private var armed = false
    private var prepared = false
    private var paused = true
    private var generation = 0
    private var phase = "idle"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.event("conversation.create", "foreground-only; speech barge-in unavailable")
        models = SpeechModels(applicationContext)
        translator = Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.LITHUANIAN).setTargetLanguage(TranslateLanguage.ENGLISH).build())
        val scroll = ScrollView(this)
        val padding = (24 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.rgb(244, 247, 242))
        }
        scroll.addView(column); setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        fun label(value: String, size: Float) = TextView(this).also {
            it.text = value; it.textSize = size; it.setTextColor(Color.rgb(17, 44, 43))
            it.setPadding(0, 12, 0, 18); column.addView(it)
        }
        fun button(value: String, action: () -> Unit) = Button(this).also {
            it.text = value; it.isAllCaps = false
            it.minHeight = (56 * resources.displayMetrics.density).toInt()
            it.setOnClickListener { action() }; column.addView(it)
        }
        label("Lithuanian → English", 30f)
        state = label("Enable the glasses, then tap their touchpad to listen.", 20f)
        state.accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        control = button("Enable glasses") { if (!active) enable() else if (paused) resumeListening() else pauseListening("phone") }
        phoneStart = button("Start from phone · without glasses taps") { enable(useTouch = false) }
        button("Stop session") { stopSession("phone") }
        result = label(savedInstanceState?.getString("translation") ?: "Your latest English translation will appear here.", 30f)
        label("After 3 seconds of silence, English plays through the selected glasses. Listening resumes after playback.", 16f)
        label("Keep this screen open. Listening pauses while translating and playing audio. Pause here to interrupt; glasses taps work only when enabled. Voice interruption is not available yet.", 14f)
        button("Setup") { startActivity(Intent(this, OnboardingActivity::class.java)) }
        button("Debug information") {
            DebugLog.event("debug.open", "from=conversation; session will stop")
            startActivity(Intent(this, DebugActivity::class.java))
        }
        glasses = GlassesCapture(this) { message -> if (!active) state.text = message }
        playback = EnglishPlayback(this) { message -> if (active) state.text = message }
    }

    private fun show(next: String, message: String) {
        phase = next
        DebugLog.event("conversation.state", "state=$next touchArmed=$armed prepared=$prepared paused=$paused generation=$generation")
        state.text = message
        control.text = if (!active) "Enable glasses" else if (paused) "Start listening" else "Pause listening"
        control.isEnabled = !active || prepared
        phoneStart.isEnabled = !active
    }

    private fun enable(useTouch: Boolean = true) {
        if (active || cleanup?.isActive == true) return
        val permissions = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_CONNECT)
        if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            DebugLog.event("conversation.blocked", "Android permissions missing")
            requestPermissions(permissions, 42)
            show("setup", "Allow microphone, camera and nearby devices, then enable the glasses again.")
            return
        }
        if (!models.installed() || !VadModels.isInstalled(this)) {
            show("setup", "Open Setup to download the speech model and pause detection first.")
            return
        }
        active = true; paused = true; prepared = false; armed = false
        DebugLog.event("conversation.enable", "touchControls=$useTouch source=meta_pcm")
        val ticket = ++generation
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        show("preparing", "Checking offline setup…")
        operation = lifecycleScope.launch {
            try {
                val downloaded = RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java).awaitLocal()
                check(downloaded.any { it.language == TranslateLanguage.LITHUANIAN }) { "Download the Lithuanian translation pack in Setup first." }
                if (!playback.canPlayOnSelectedDevice()) {
                    val selected = suspendCancellableCoroutine { continuation ->
                        playback.selectDevice { success -> if (continuation.isActive) continuation.resume(success) }
                    }
                    check(selected) { "Choose connected glasses for English playback, then enable again." }
                }
                currentCoroutineContext().ensureActive()
                if (useTouch) glasses.arm({ onGlassesTap() }, { stopSession("glasses_back") }, { error -> failSession("input", IllegalStateException(error)) })
                if (ticket != generation || !active) return@launch
                prepared = true
                armed = useTouch
                if (useTouch) show("armed", "Ready. Tap the glasses touchpad to start listening, or tap Start listening.")
                else {
                    show("ready", "Phone controls selected. Glasses taps are disabled.")
                    resumeListening()
                }
            } catch (e: TimeoutCancellationException) { if (ticket == generation) failSession("enable_timeout", IllegalStateException("Glasses setup timed out. Check connection and permissions in Setup.", e)) }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) { if (ticket == generation) failSession("enable", e) }
        }
    }

    private fun onGlassesTap() {
        DebugLog.event("conversation.tap", "phase=$phase active=$active paused=$paused")
        if (!active || !armed) return
        if (paused) resumeListening() else pauseListening("glasses_tap")
    }

    private fun resumeListening() {
        if (!active || !prepared || !paused) return
        paused = false
        val ticket = ++generation
        val previous = operation
        operation = lifecycleScope.launch {
            previous?.join(); cleanup?.join()
            if (!current(ticket)) return@launch
            try {
                while (current(ticket)) {
                    DebugLog.beginTurn("lt_en", "meta_pcm_conversation")
                    val buffer = PcmTurn()
                    val endpoint = SpeechEndpoint.create(applicationContext)
                    val ended = CompletableDeferred<Unit>()
                    withCaptureCleanup(
                        stopCapture = { withContext(NonCancellable) { glasses.stopCapture() } },
                        closeDetector = { endpoint.close() }
                    ) {
                        show("listening", "Listening to Lithuanian…")
                        glasses.start(buffer, endpoint, { ended.complete(Unit) }, { message -> ended.completeExceptionally(IllegalStateException(message)) })
                        withTimeout(35000) { ended.await() }
                    }
                    if (!current(ticket)) break
                    if (!endpoint.hasSpeechEver()) {
                        DebugLog.event("conversation.silence", "discardedSamples=${buffer.size()}; restart capture without recognition")
                        continue
                    }
                    DebugLog.event("conversation.capture.inactive", "reason=recognition_translation_playback; bargeIn=false")
                    val samples = buffer.finish()
                    show("recognizing", "Understanding Lithuanian…")
                    val recognized = withContext(Dispatchers.IO) { models.recognize(samples) {} }
                    if (!current(ticket)) break
                    show("translating", "Translating to English…")
                    val began = SystemClock.elapsedRealtime()
                    DebugLog.event("translation.start", "direction=lt_en inputCharacters=${recognized.length}")
                    val translated = translator.translate(recognized).awaitLocal()
                    if (!current(ticket)) break
                    DebugLog.event("translation.success", "outputCharacters=${translated.length} elapsedMs=${SystemClock.elapsedRealtime()-began}")
                    check(translated.isNotBlank()) { "Translation returned no text." }
                    result.text = translated
                    show("playing", "Playing English through the glasses…")
                    val outcome = withTimeout(90000) {
                        suspendCancellableCoroutine { continuation ->
                            playback.playOnSelectedDevice(translated) { value -> if (continuation.isActive) continuation.resume(value) }
                        }
                    }
                    if (!current(ticket)) break
                    check(outcome == EnglishPlayback.PlaybackResult.SUCCESS) { "Playback did not finish. Check the glasses audio connection and Debug information." }
                    DebugLog.event("conversation.restart", "playback completed; begin next turn")
                }
            } catch (e: TimeoutCancellationException) { if (current(ticket)) failSession("timeout", IllegalStateException("Conversation timed out during $phase.", e)) }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) { if (current(ticket)) failSession("loop", e) }
        }
    }

    private fun current(ticket: Int) = active && !paused && ticket == generation

    private fun pauseListening(reason: String) {
        if (!active || paused) return
        DebugLog.event("conversation.pause", "reason=$reason phase=$phase")
        paused = true; generation++
        val previous = operation
        previous?.cancel(); playback.stop()
        cleanup = lifecycleScope.launch {
            previous?.join()
            try { glasses.stopCapture() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failSession("pause_cleanup", e); return@launch }
            if (active && paused) show(if (armed) "armed" else "ready", if (armed) "Paused. Tap the glasses to listen again." else "Paused. Tap Start listening on the phone to resume.")
        }
        show("pausing", "Pausing…")
    }

    private fun failSession(stage: String, error: Throwable) {
        DebugLog.error("conversation.$stage.error", error)
        stopSession("error", "${error.message ?: "Conversation failed"} Open Debug information for the failed step.${if (stage == "input" || stage.startsWith("enable")) " You can try Start from phone without glasses taps." else ""}")
    }

    private fun stopSession(reason: String, message: String = "Stopped. Enable the glasses when you are ready.") {
        DebugLog.event("conversation.stop", "reason=$reason phase=$phase")
        active = false; armed = false; prepared = false; paused = true; generation++
        val previous = operation
        val oldCleanup = cleanup
        previous?.cancel()
        if (::playback.isInitialized) playback.stop()
        cleanup = lifecycleScope.launch {
            previous?.join(); oldCleanup?.join()
            if (::glasses.isInitialized) {
                try { glasses.stop() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { DebugLog.error("conversation.stop.cleanup", e) }
            }
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        show("stopped", message)
    }

    override fun onSaveInstanceState(outState: Bundle) { outState.putString("translation", result.text.toString()); super.onSaveInstanceState(outState) }
    override fun onPause() { if (::glasses.isInitialized) stopSession("lifecycle_pause"); super.onPause() }
    override fun onDestroy() { if (::glasses.isInitialized) glasses.close(); if (::playback.isInitialized) playback.close(); if (::translator.isInitialized) translator.close(); super.onDestroy() }

    private suspend fun <T> Task<T>.awaitLocal(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
        addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.cancel() }
    }
}
