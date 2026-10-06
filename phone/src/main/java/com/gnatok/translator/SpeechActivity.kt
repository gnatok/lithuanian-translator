package com.gnatok.translator

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.*
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.gnatok.translator.core.PcmTurn
import com.gnatok.translator.core.WavReader
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpeechActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var models: SpeechModels
    private lateinit var glasses: GlassesCapture
    private var turn: PcmTurn? = null
    private var recording = false
    private var working = false
    private var generation = 0
    private var work: Job? = null
    private var microphone: Job? = null
    private var timer: Job? = null
    // Native decode is not interruptible. Serialize canceled and subsequent turns until release finishes.
    private val inference = Mutex()
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) beginWork {
            val samples = withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)!!.use { WavReader.read(it) } }
            recognize(samples)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        models = SpeechModels(applicationContext)
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32); setBackgroundColor(Color.rgb(244,247,242)) }
        scroll.addView(column); setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars()); view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        fun text(value: String, size: Float = 18f) = TextView(this).also { it.text = value; it.textSize = size; it.setTextColor(Color.rgb(17,44,43)); column.addView(it) }
        fun button(value: String, action: () -> Unit) { column.addView(Button(this).apply { this.text = value; isAllCaps = false; setOnClickListener { action() } }) }
        text(if (intent.getStringExtra("language") == "en") "Speak English → Lithuanian" else "Speak Lithuanian → English", 28f)
        text("Short turns, up to 20 seconds. Tap Finish to recognize and translate. Leaving this screen cancels capture. No audio is saved.")
        status = text(if (models.installed()) "Speech model installed; verified again before recognition." else "Speech model needs a one-time download (about 670 MB).")
        glasses = GlassesCapture(this) { if (!recording && !working) status.text = it }
        button("Download Parakeet on Wi-Fi") {
            if (recording || working) return@button
            AlertDialog.Builder(this).setTitle("Download offline speech model?")
                .setMessage("About 670 MB over unmetered Wi-Fi. Keep this screen open. Verified completed files are reused after interruption; an incomplete file restarts.")
                .setPositiveButton("Download") { _, _ -> beginWork {
                    withContext(Dispatchers.IO) { models.install { message -> report(message) } }
                    status.text = "Speech model ready. Download the translation pack on the main screen too."
                } }.setNegativeButton("Cancel", null).show()
        }
        button("Connect app to Meta AI") { if (!working && !recording && permissionsGranted()) runCatching { glasses.register() }.onFailure { status.text = it.message } }
        button("Grant glasses camera + microphone access") { if (!working && !recording && permissionsGranted()) runCatching { glasses.permissions() }.onFailure { status.text = it.message } }
        text("Experimental glasses audio also activates camera streaming. Video is discarded. Enable Developer Mode in Meta AI; firmware/access support still needs testing.", 16f)
        button("Start glasses turn") { startTurn(false) }
        button("Start PHONE microphone baseline") { startTurn(true) }
        button("Finish turn and translate") { finishTurn() }
        button("Import 16 kHz mono PCM16 WAV") { if (!working && !recording) picker.launch(arrayOf("audio/*", "application/octet-stream")) }
        button("Cancel current work") { cancelWork(); status.text = "Canceled. No audio retained." }
        text("Recognition runs on this phone. Direction is chosen on the previous screen; the transcript is shown there before the translated result. First-turn model loading may be slow. TTS and automatic voice activity detection are not enabled yet.", 16f)
    }
    private fun permissionsGranted(): Boolean {
        val required = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.CAMERA)
        if (required.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) return true
        requestPermissions(required, 30); status.text = "Grant permissions, then tap the button again."; return false
    }
    private fun report(message: String) { val ticket = generation; runOnUiThread { if (ticket == generation && !isDestroyed) status.text = message } }
    private fun beginWork(block: suspend () -> Unit) {
        if (working || recording) return
        working = true; val ticket = ++generation
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        work = lifecycleScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (ticket == generation) status.text = e.message ?: "Speech operation failed" }
            catch (e: OutOfMemoryError) { if (ticket == generation) status.text = "Not enough memory for Parakeet. Close other apps and try a shorter turn." }
            catch (e: UnsatisfiedLinkError) { if (ticket == generation) status.text = "Speech runtime unavailable on this device: ${e.message}" }
            finally { if (ticket == generation) { working = false; window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } }
        }
    }
    private fun startTurn(phone: Boolean) {
        if (working || recording || !permissionsGranted()) return
        if (!models.installed()) { status.text = "Download the speech model first."; return }
        recording = true; generation++; val ticket = generation
        val buffer = PcmTurn(); turn = buffer
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text = if (phone) "Recording PHONE microphone baseline…" else "Connecting glasses PCM…"
        work = lifecycleScope.launch {
            try {
                if (phone) startPhone(buffer, ticket)
                else glasses.start(buffer, { if (ticket == generation) finishTurn() }, { message -> if (ticket == generation) { cancelWork(); status.text = message } })
            } catch (e: Exception) { if (e !is CancellationException && ticket == generation) { cancelWork(); status.text = e.message } }
        }
        timer = lifecycleScope.launch {
            val started = SystemClock.elapsedRealtime()
            while (recording && ticket == generation) {
                delay(1000)
                if (buffer.size() > 0) status.text = "${if (phone) "PHONE baseline" else "Glasses PCM"} • ${buffer.size() / 16000} / 20 seconds. Tap Finish."
                if (SystemClock.elapsedRealtime() - started >= 30000) { if (buffer.size() > 0) finishTurn() else { cancelWork(); status.text = "No audio received" }; break }
            }
        }
    }
    @SuppressLint("MissingPermission") // Explicit permission gate above; runtime revocation is handled.
    private fun startPhone(buffer: PcmTurn, ticket: Int) {
        microphone = lifecycleScope.launch(Dispatchers.IO) {
            var recorder: AudioRecord? = null
            try {
                val manager = getSystemService(AudioManager::class.java)
                val builtIn = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                    ?: error("Phone microphone unavailable")
                val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0) { "16 kHz recording unavailable" }
                recorder = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC).setAudioFormat(
                    AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                ).setBufferSizeInBytes(maxOf(minimum,6400)).build()
                check(recorder.setPreferredDevice(builtIn)) { "Phone microphone route rejected" }
                recorder.startRecording()
                val shorts = ShortArray(1600); var position = 0L
                while (currentCoroutineContext().isActive) {
                    val read = recorder.read(shorts,0,shorts.size,AudioRecord.READ_BLOCKING)
                    check(read > 0) { "Microphone read failed: $read" }
                    check(recorder.routedDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) { "Actual input is not the phone microphone; stopped" }
                    val bytes = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until read) bytes.putShort(shorts[i]); bytes.flip()
                    val full = buffer.append(bytes, position * 1000000 / 16000); position += read
                    if (full) { withContext(Dispatchers.Main) { if (ticket == generation) finishTurn() }; break }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { withContext(Dispatchers.Main) { if (ticket == generation) { cancelWork(); status.text = e.message } } }
            finally { recorder?.let { runCatching { it.stop() }; it.release() } }
        }
    }
    private fun finishTurn() {
        if (!recording) return
        recording = false; timer?.cancel()
        val captured = turn ?: return; turn = null
        val previous = work
        beginWork {
            previous?.join(); microphone?.cancelAndJoin(); microphone = null; glasses.stop()
            recognize(captured.finish())
        }
    }
    private suspend fun recognize(samples: FloatArray) {
        val started = SystemClock.elapsedRealtime()
        val text = withContext(Dispatchers.IO) { inference.withLock { models.recognize(samples) { report(it) } } }
        currentCoroutineContext().ensureActive()
        setResult(RESULT_OK, Intent().putExtra("transcript",text).putExtra("elapsedMs",SystemClock.elapsedRealtime()-started))
        finish()
    }
    private fun cancelWork() {
        generation++; recording = false; working = false; turn = null
        work?.cancel(); microphone?.cancel(); timer?.cancel()
        // Serialize teardown before accepting a new capture session.
        working = true
        val ticket = generation
        lifecycleScope.launch {
            try { microphone?.join(); glasses.stop() }
            finally { if (ticket == generation) working = false }
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    override fun onStop() { super.onStop(); if (::glasses.isInitialized) { cancelWork(); status.text = "Paused. Tap a button to begin again." } }
    override fun onDestroy() { if (::glasses.isInitialized) glasses.close(); super.onDestroy() }
}
