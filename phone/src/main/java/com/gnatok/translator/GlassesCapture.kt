package com.gnatok.translator

import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.gnatok.translator.core.PcmTurn
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.*
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.*

/** Experimental DAT camera+PCM capture, intentionally foreground-only. Never substitutes phone audio. */
internal class GlassesCapture(private val activity: ComponentActivity, private val status: (String) -> Unit) {
    private var initialized = false
    private var requestingMicrophone = false
    private var camera: Camera? = null
    private var session: DeviceSession? = null
    private var captureJob: Job? = null
    private val permissionLauncher = activity.registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        if (result.getOrDefault(PermissionStatus.Denied) != PermissionStatus.Granted) {
            status("Glasses permission denied. Camera and microphone access are both required.")
        } else if (!requestingMicrophone) {
            requestingMicrophone = true
            requestMicrophone()
        } else status("Glasses permissions granted. Return here and tap Start glasses turn.")
    }
    // Separate method avoids referencing the launcher while Kotlin initializes it.
    private fun requestMicrophone() { permissionLauncher.launch(Permission.MICROPHONE) }
    fun initialize() {
        if (initialized) return
        Wearables.initialize(activity)
        initialized = true
        activity.lifecycleScope.launch {
            Wearables.registrationState.collect { status("Meta registration: $it") }
        }
    }
    fun register() { initialize(); Wearables.startRegistration(activity) }
    fun permissions() { initialize(); requestingMicrophone = false; permissionLauncher.launch(Permission.CAMERA) }
    suspend fun start(turn: PcmTurn, endpoint: SpeechEndpoint?, complete: () -> Unit, failed: (String) -> Unit) {
        initialize()
        for (permission in listOf(Permission.CAMERA, Permission.MICROPHONE)) {
            check(Wearables.checkPermissionStatus(permission).getOrDefault(PermissionStatus.Denied) == PermissionStatus.Granted) {
                "Grant glasses camera and microphone permissions first"
            }
        }
        check(session == null) { "A glasses session is already active" }
        val parent = SupervisorJob(activity.lifecycleScope.coroutineContext[Job])
        captureJob = parent
        val scope = CoroutineScope(parent + Dispatchers.Main.immediate)
        Wearables.createSession(AutoDeviceSelector()).onSuccess { created ->
            session = created
            scope.launch { created.errors.collect { failed("Glasses session: ${it.description}") } }
            scope.launch {
                var wasStarted = false
                created.state.collect { state ->
                    if (state == DeviceSessionState.STARTED && camera == null) {
                        wasStarted = true
                        created.addCamera(StreamConfiguration(
                            audioCodec = AudioCodec.PCM(AudioSampleRate.RATE_16000, 1),
                            videoQuality = VideoQuality.LOW, frameRate = 2, compressVideo = true
                        )).onSuccess { added ->
                            camera = added
                            scope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                                try {
                                    added.stream.audioStream.collect { frame ->
                                        val full = turn.append(frame.buffer, frame.presentationTimeUs)
                                        val ended = endpoint?.acceptPcm16(frame.buffer) == true
                                        if (full || ended) withContext(Dispatchers.Main) { complete() }
                                    }
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { withContext(Dispatchers.Main) { failed(e.message ?: "Invalid PCM audio") } }
                            }
                            // Consume and discard compressed video; nothing is recorded or displayed.
                            scope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { added.stream.videoStream.collect {} }
                            scope.launch { added.stream.errorStream.collect { failed("Glasses stream: ${it.description}") } }
                            scope.launch {
                                var streaming = false
                                added.stream.state.collect { streamState ->
                                    if (streamState == StreamState.STREAMING) streaming = true
                                    if (streaming && streamState in listOf(StreamState.STOPPED, StreamState.CLOSED, StreamState.PAUSED))
                                        failed("Glasses stream interrupted; repeat this turn")
                                }
                            }
                            added.stream.start().onFailure { error, _ -> failed(error.description) }
                        }.onFailure { error, _ -> failed(error.description) }
                    } else if (wasStarted && state == DeviceSessionState.STOPPED) failed("Glasses disconnected")
                }
            }
            created.start()
            scope.launch {
                delay(10000)
                if (turn.size() == 0) failed("No glasses PCM received. Check registration, firmware and experimental audio access.")
            }
        }.onFailure { error, _ -> failed(error.description) }
    }
    suspend fun stop() {
        captureJob?.cancelAndJoin(); captureJob = null
        try { camera?.stop() } finally { camera = null; session?.stop(); session = null }
    }
    fun close() {
        captureJob?.cancel(); captureJob = null
        try { camera?.stop() } finally { camera = null; session?.stop(); session = null }
    }
}
