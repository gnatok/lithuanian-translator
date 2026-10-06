package com.gnatok.translator

import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.gnatok.translator.core.PcmTurn
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.removeCamera
import com.meta.wearable.dat.camera.types.*
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.inputs.Inputs
import com.meta.wearable.dat.inputs.addInputs
import com.meta.wearable.dat.inputs.removeInputs
import com.meta.wearable.dat.inputs.types.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Experimental DAT camera+PCM capture, intentionally foreground-only. Never substitutes phone audio. */
internal class GlassesCapture(private val activity: ComponentActivity, private val status: (String) -> Unit) {
    private var initialized = false
    private var requestingMicrophone = false
    private var camera: Camera? = null
    private var session: DeviceSession? = null
    private var captureJob: Job? = null
    private var sessionJob: Job? = null
    private var inputsJob: Job? = null
    private var inputs: Inputs? = null
    private var sessionFailure: ((String) -> Unit)? = null
    private val operations = Mutex()
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
    private suspend fun ensureSession(failed: (String) -> Unit): DeviceSession {
        initialize()
        session?.let { existing ->
            check(existing.state.value == DeviceSessionState.STARTED) { "Glasses session is not ready. Stop and reconnect." }
            return existing
        }
        sessionFailure = failed
        var createdSession: DeviceSession? = null
        var creationError: String? = null
        Wearables.createSession(AutoDeviceSelector())
            .onSuccess { createdSession = it }
            .onFailure { error, _ -> creationError = error.description }
        val created = createdSession ?: error(creationError ?: "Could not create glasses session")
        session = created
        val parent = SupervisorJob(activity.lifecycleScope.coroutineContext[Job])
        sessionJob = parent
        val scope = CoroutineScope(parent + Dispatchers.Main.immediate)
        scope.launch { created.errors.collect {
            DebugLog.event("meta.session.error", it.description)
            sessionFailure?.invoke("Glasses session: ${it.description}")
        } }
        scope.launch {
            var wasStarted = false
            created.state.collect { state ->
                DebugLog.event("meta.session.state", "$state")
                if (state == DeviceSessionState.STARTED) wasStarted = true
                if (wasStarted && state == DeviceSessionState.STOPPED) sessionFailure?.invoke("Glasses disconnected")
            }
        }
        DebugLog.event("meta.session.start", "requested")
        created.start()
        withTimeout(15000) { created.state.first { it == DeviceSessionState.STARTED } }
        return created
    }

    /** Arm only from a visible user action. Inputs does not cold-launch this application. */
    suspend fun arm(onTap: () -> Unit, onBack: () -> Unit, failed: (String) -> Unit) = operations.withLock {
        check(inputs == null) { "Glasses controls already armed" }
        val current = ensureSession(failed)
        sessionFailure = failed
        var attached: Inputs? = null
        var failure: String? = null
        current.addInputs(InputsConfiguration(sources = setOf(InputSource.CAPTOUCH), consumeBack = true))
            .onSuccess { attached = it }
            .onFailure { error, _ -> failure = error.description }
        val controls = attached ?: error("Glasses touch controls unavailable: ${failure ?: "unknown error"}. Check Inputs approval in Wearables Developer Center.")
        inputs = controls
        val parent = SupervisorJob(sessionJob)
        inputsJob = parent
        val scope = CoroutineScope(parent + Dispatchers.Main.immediate)
        val activation = CompletableDeferred<Unit>()
        scope.launch {
            var activated = false
            controls.state.collect { state ->
                DebugLog.event("meta.inputs.state", "$state")
                if (state == InputsState.ACTIVE) { activated = true; activation.complete(Unit) }
                if (activated && state == InputsState.INACTIVE) failed("Glasses touch controls disconnected. Stop and re-arm the session.")
            }
        }
        scope.launch { controls.errors.collect { error ->
            error?.let {
                DebugLog.event("meta.inputs.error", it.description)
                val message = "Glasses touch controls: ${it.description}"
                if (!activation.isCompleted) activation.completeExceptionally(IllegalStateException(message))
                else failed(message)
            }
        } }
        scope.launch {
            var lastTap = 0L
            controls.events.collect { event ->
                DebugLog.event("meta.inputs.event", "${event.javaClass.simpleName} source=${event.source}")
                when (event) {
                    is InputEvent.Select -> if (event.source == InputSource.CAPTOUCH) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastTap >= 450) { lastTap = now; onTap() }
                    }
                    is InputEvent.Back -> onBack()
                    is InputEvent.Nav, is InputEvent.Button, is InputEvent.Capture, is InputEvent.Drag -> Unit
                }
            }
        }
        try { withTimeout(10000) { activation.await() } }
        catch (error: Throwable) {
            withContext(NonCancellable) {
                parent.cancelAndJoin(); inputsJob = null
                runCatching { current.removeInputs() }
                inputs = null
            }
            throw error
        }
        DebugLog.event("meta.inputs.armed", "CAPTOUCH Select toggles conversation; Back stops")
    }

    suspend fun start(turn: PcmTurn, endpoint: SpeechEndpoint?, complete: () -> Unit, failed: (String) -> Unit) = operations.withLock {
        initialize()
        DebugLog.event("meta.capture.start", "PCM16 mono 16000Hz; video LOW 2fps discarded")
        for (permission in listOf(Permission.CAMERA, Permission.MICROPHONE)) {
            val permissionStatus = Wearables.checkPermissionStatus(permission).getOrDefault(PermissionStatus.Denied)
            DebugLog.event("meta.permission.check", "$permission=$permissionStatus")
            check(permissionStatus == PermissionStatus.Granted) { "Grant glasses camera and microphone permissions first" }
        }
        check(camera == null) { "A glasses recording is already active" }
        val current = ensureSession(failed)
        if (inputs == null) sessionFailure = failed
        val parent = SupervisorJob(sessionJob)
        captureJob = parent
        val scope = CoroutineScope(parent + Dispatchers.Main.immediate)
        var attached: Camera? = null
        var failure: String? = null
        current.addCamera(StreamConfiguration(
            audioCodec = AudioCodec.PCM(AudioSampleRate.RATE_16000, 1),
            videoQuality = VideoQuality.LOW, frameRate = 2, compressVideo = true
        )).onSuccess { attached = it }.onFailure { error, _ -> failure = error.description }
        val added = attached ?: error(failure ?: "Could not attach glasses camera")
        camera = added
        DebugLog.event("meta.camera.created", "PCM stream attached")
        scope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try {
                added.stream.audioStream.collect { frame ->
                    DebugLog.audio("meta_pcm", frame.buffer, frame.presentationTimeUs)
                    val full = turn.append(frame.buffer, frame.presentationTimeUs)
                    val ended = endpoint?.acceptPcm16(frame.buffer) == true
                    if (full || ended) withContext(Dispatchers.Main) {
                        DebugLog.event("capture.endpoint", if (full) "sample_limit" else "vad_silence")
                        complete()
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { DebugLog.error("meta.pcm.error", e); withContext(Dispatchers.Main) { failed(e.message ?: "Invalid PCM audio") } }
        }
        scope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { added.stream.videoStream.collect {} }
        scope.launch { added.stream.errorStream.collect { DebugLog.event("meta.stream.error", it.description); failed("Glasses stream: ${it.description}") } }
        scope.launch {
            var streaming = false
            added.stream.state.collect { state ->
                DebugLog.event("meta.stream.state", "$state")
                if (state == StreamState.STREAMING) streaming = true
                if (streaming && state in listOf(StreamState.STOPPED, StreamState.CLOSED, StreamState.PAUSED))
                    failed("Glasses stream interrupted; repeat this turn")
            }
        }
        added.stream.start().onFailure { error, _ -> DebugLog.event("meta.stream.start.error", error.description); failed(error.description) }
        scope.launch {
            delay(10000)
            if (turn.size() == 0) failed("No glasses PCM received. Check registration, firmware and experimental audio access.")
        }
    }

    private suspend fun stopCaptureLocked() {
        captureJob?.cancelAndJoin(); captureJob = null
        val previous = camera ?: return
        camera = null
        // On some DAT 1.0 paths stop() also detaches. Explicit removal covers the others.
        var failure: Throwable? = null
        try { previous.stop() } catch (error: Throwable) { failure = error }
        try {
            session?.removeCamera()?.onFailure { error, _ ->
                val detachFailure = cameraDetachFailure(error)
                if (detachFailure == null) {
                    DebugLog.event("meta.camera.remove", "already detached")
                } else {
                    DebugLog.event("meta.camera.remove.error", error.description)
                    throw detachFailure
                }
            }
        } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }
    /** End audio/video only, retaining the input-only armed session between conversation turns. */
    suspend fun stopCapture() = operations.withLock {
        DebugLog.event("meta.capture.stop", "retain armed inputs=${inputs != null}")
        stopCaptureLocked()
    }
    suspend fun stop() = operations.withLock {
        DebugLog.event("meta.session.stop", "full teardown")
        try { stopCaptureLocked() }
        catch (error: CancellationException) { throw error }
        catch (error: Throwable) { DebugLog.error("meta.session.cleanup.camera", error) }
        finally { withContext(NonCancellable) {
            // A canceled caller must still release every native capability and the session.
            runCatching { camera?.stop() }
            camera = null
            inputsJob?.cancelAndJoin(); inputsJob = null
            if (inputs != null) runCatching { session?.removeInputs()?.onFailure { error, _ -> DebugLog.event("meta.inputs.remove.error", error.description) } }
            inputs = null
            sessionJob?.cancelAndJoin(); sessionJob = null
            sessionFailure = null
            runCatching { session?.stop() }.onFailure { DebugLog.error("meta.session.cleanup.stop", it) }
            session = null
        } }
    }
    fun close() {
        DebugLog.event("meta.capture.close", "activity destroyed")
        captureJob?.cancel(); inputsJob?.cancel(); sessionJob?.cancel()
        captureJob = null; inputsJob = null; sessionJob = null; sessionFailure = null
        runCatching { camera?.stop() }.onFailure { DebugLog.error("meta.close.camera", it) }
        if (camera != null) runCatching { session?.removeCamera() }
        camera = null
        if (inputs != null) runCatching { session?.removeInputs() }
        inputs = null
        runCatching { session?.stop() }.onFailure { DebugLog.error("meta.close.session", it) }
        session = null
    }
}
