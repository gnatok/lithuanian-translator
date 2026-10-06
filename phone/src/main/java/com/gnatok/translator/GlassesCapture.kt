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
        DebugLog.event("meta.permission.result", "permission=${if (requestingMicrophone) "microphone" else "camera"} status=${result.getOrDefault(PermissionStatus.Denied)}")
        if (result.getOrDefault(PermissionStatus.Denied) != PermissionStatus.Granted) {
            status("Glasses permission denied. Camera and microphone access are both required.")
        } else if (!requestingMicrophone) {
            requestingMicrophone = true
            requestMicrophone()
        } else status("Glasses permissions granted. Return here and tap Start glasses turn.")
    }
    // Separate method avoids referencing the launcher while Kotlin initializes it.
    private fun requestMicrophone() { DebugLog.event("meta.permission.request", "microphone"); permissionLauncher.launch(Permission.MICROPHONE) }
    fun initialize() {
        if (initialized) return
        DebugLog.event("meta.initialize", "begin")
        Wearables.initialize(activity)
        initialized = true
        DebugLog.event("meta.initialize", "complete")
        activity.lifecycleScope.launch {
            Wearables.registrationState.collect { DebugLog.event("meta.registration.state", "$it"); status("Meta registration: $it") }
        }
    }
    fun register() { initialize(); DebugLog.event("meta.registration.request", "launch"); Wearables.startRegistration(activity) }
    fun permissions() { initialize(); requestingMicrophone = false; DebugLog.event("meta.permission.request", "camera"); permissionLauncher.launch(Permission.CAMERA) }
    suspend fun start(turn: PcmTurn, endpoint: SpeechEndpoint?, complete: () -> Unit, failed: (String) -> Unit) {
        initialize()
        DebugLog.event("meta.capture.start", "PCM16 mono 16000Hz; video LOW 2fps discarded")
        for (permission in listOf(Permission.CAMERA, Permission.MICROPHONE)) {
            val permissionStatus = Wearables.checkPermissionStatus(permission).getOrDefault(PermissionStatus.Denied)
            DebugLog.event("meta.permission.check", "$permission=$permissionStatus")
            check(permissionStatus == PermissionStatus.Granted) {
                "Grant glasses camera and microphone permissions first"
            }
        }
        check(session == null) { "A glasses session is already active" }
        val parent = SupervisorJob(activity.lifecycleScope.coroutineContext[Job])
        captureJob = parent
        val scope = CoroutineScope(parent + Dispatchers.Main.immediate)
        Wearables.createSession(AutoDeviceSelector()).onSuccess { created ->
            DebugLog.event("meta.session.created", "automatic device selector")
            session = created
            scope.launch { created.errors.collect { DebugLog.event("meta.session.error", it.description); failed("Glasses session: ${it.description}") } }
            scope.launch {
                var wasStarted = false
                created.state.collect { state ->
                    DebugLog.event("meta.session.state", "$state")
                    if (state == DeviceSessionState.STARTED && camera == null) {
                        wasStarted = true
                        created.addCamera(StreamConfiguration(
                            audioCodec = AudioCodec.PCM(AudioSampleRate.RATE_16000, 1),
                            videoQuality = VideoQuality.LOW, frameRate = 2, compressVideo = true
                        )).onSuccess { added ->
                            DebugLog.event("meta.camera.created", "PCM stream attached")
                            camera = added
                            scope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                                try {
                                    added.stream.audioStream.collect { frame ->
                                        DebugLog.audio("meta_pcm", frame.buffer, frame.presentationTimeUs)
                                        val full = turn.append(frame.buffer, frame.presentationTimeUs)
                                        val ended = endpoint?.acceptPcm16(frame.buffer) == true
                                        if (full || ended) withContext(Dispatchers.Main) { DebugLog.event("capture.endpoint", if (full) "sample_limit" else "vad_silence"); complete() }
                                    }
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { DebugLog.error("meta.pcm.error", e); withContext(Dispatchers.Main) { failed(e.message ?: "Invalid PCM audio") } }
                            }
                            // Consume and discard compressed video; nothing is recorded or displayed.
                            scope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { added.stream.videoStream.collect {} }
                            scope.launch { added.stream.errorStream.collect { DebugLog.event("meta.stream.error", it.description); failed("Glasses stream: ${it.description}") } }
                            scope.launch {
                                var streaming = false
                                added.stream.state.collect { streamState ->
                                    DebugLog.event("meta.stream.state", "$streamState")
                                    if (streamState == StreamState.STREAMING) streaming = true
                                    if (streaming && streamState in listOf(StreamState.STOPPED, StreamState.CLOSED, StreamState.PAUSED))
                                        failed("Glasses stream interrupted; repeat this turn")
                                }
                            }
                            DebugLog.event("meta.stream.start", "requested")
                            added.stream.start().onFailure { error, _ -> DebugLog.event("meta.stream.start.error", error.description); failed(error.description) }
                        }.onFailure { error, _ -> DebugLog.event("meta.camera.error", error.description); failed(error.description) }
                    } else if (wasStarted && state == DeviceSessionState.STOPPED) failed("Glasses disconnected")
                }
            }
            DebugLog.event("meta.session.start", "requested")
            created.start()
            scope.launch {
                delay(10000)
                if (turn.size() == 0) { DebugLog.event("meta.pcm.timeout", "no samples after 10000ms"); failed("No glasses PCM received. Check registration, firmware and experimental audio access.") }
            }
        }.onFailure { error, _ -> DebugLog.event("meta.session.create.error", error.description); failed(error.description) }
    }
    suspend fun stop() {
        DebugLog.event("meta.capture.stop", "camera=${camera != null} session=${session != null}")
        captureJob?.cancelAndJoin(); captureJob = null
        try { camera?.stop() } finally { camera = null; session?.stop(); session = null }
    }
    fun close() {
        DebugLog.event("meta.capture.close", "activity destroyed")
        captureJob?.cancel(); captureJob = null
        try { camera?.stop() } finally { camera = null; session?.stop(); session = null }
    }
}
