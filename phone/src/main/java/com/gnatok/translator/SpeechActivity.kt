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
    private var endpoint: SpeechEndpoint? = null
    private var autoFinish = true
    private var watchArmed = false
    private var phoneSource = false
    private var language = "lt"
    private lateinit var heading: TextView
    private lateinit var watch: WatchBridge
    private lateinit var sourceChoice: RadioGroup
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) beginWork {
            val samples = withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)!!.use { WavReader.read(it) } }
            recognize(samples)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        models = SpeechModels(applicationContext)
        language = savedInstanceState?.getString("language") ?: intent.getStringExtra("language") ?: "lt"
        watchArmed = intent.getBooleanExtra("watchArmed", false)
        phoneSource = getPreferences(MODE_PRIVATE).getBoolean("phoneSource",false)
        val scroll = ScrollView(this)
        val padding = (20 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, padding, padding, padding); setBackgroundColor(Color.rgb(244,247,242)) }
        scroll.addView(column); setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars()); view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        fun text(value: String, size: Float = 18f) = TextView(this).also { it.text = value; it.textSize = size; it.setPadding(0,8,0,12); it.setTextColor(Color.rgb(17,44,43)); column.addView(it) }
        fun button(value: String, action: () -> Unit) { column.addView(Button(this).apply { this.text = value; isAllCaps = false; minHeight=(52*resources.displayMetrics.density).toInt(); setOnClickListener { action() } },LinearLayout.LayoutParams(-1,-2)) }
        heading = text(if (language == "en") "Speak English → Lithuanian" else "Listen: Lithuanian → English", 28f)
        heading.setTypeface(null,android.graphics.Typeface.BOLD)
        text("Short turns, up to 20 seconds. Tap Finish to recognize and translate. Leaving this screen cancels capture. No audio is saved.")
        status = text(if (models.installed()) "Speech model installed. Ready for a short turn." else "Speech model needs a one-time download (about 670 MB).")
        status.accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        sourceChoice = RadioGroup(this).apply {
            addView(RadioButton(this@SpeechActivity).apply { id=1; text="Glasses • Meta PCM" })
            addView(RadioButton(this@SpeechActivity).apply { id=2; text="Phone microphone • comparison test" })
            check(if(phoneSource)2 else 1)
            setOnCheckedChangeListener { _, id ->
                if(recording || working) { if((id==2)!=phoneSource) check(if(phoneSource)2 else 1) }
                else { phoneSource=id==2;getPreferences(MODE_PRIVATE).edit().putBoolean("phoneSource",phoneSource).apply();publishWatch() }
            }
        };column.addView(sourceChoice)
        column.addView(Switch(this).apply { text="Auto-finish after a pause • applies to next turn";isChecked=true;setOnCheckedChangeListener { _, value -> autoFinish=value } })
        column.addView(Switch(this).apply { text="Enable watch controls while this screen is open";isChecked=watchArmed;setOnCheckedChangeListener { _, value -> watchArmed=value;publishWatch() } })
        button("Start turn") { startTurn(phoneSource) }
        button("Finish and translate") { finishTurn() }
        button("Cancel current turn") { cancelWork();status.text="Canceled. No audio retained." }
        text("SETUP & TEST INPUTS",14f)
        glasses = GlassesCapture(this) { if (!recording && !working) status.text = it }
        watch = WatchBridge(this) { action -> watchCommand(action) }
        button("Download Parakeet on Wi-Fi") {
            if (recording || working) return@button
            AlertDialog.Builder(this).setTitle("Download offline speech model?")
                .setMessage("About 670 MB over unmetered Wi-Fi. Keep this screen open. Verified completed files are reused after interruption; an incomplete file restarts.")
                .setPositiveButton("Download") { _, _ -> beginWork {
                    val ticket = generation
                    withContext(Dispatchers.IO) { models.install { message -> report(message, ticket) } }
                    report("Downloading optional speech detection…",ticket)
                    VadModels.install(applicationContext)
                    status.text = "Speech and pause detection ready. Download the translation pack on the main screen too."
                } }.setNegativeButton("Cancel", null).show()
        }
        button("Connect app to Meta AI") { if (!working && !recording && permissionsGranted()) runCatching { glasses.register() }.onFailure { status.text = it.message } }
        button("Grant glasses camera + microphone access") { if (!working && !recording && permissionsGranted()) runCatching { glasses.permissions() }.onFailure { status.text = it.message } }
        text("Experimental glasses audio also activates camera streaming. Video is discarded. Enable Developer Mode in Meta AI; firmware/access support still needs testing.", 16f)
        button("Download / repair pause detection only") { beginWork { VadModels.install(applicationContext);status.text="Automatic pause detection ready." } }
        button("Import 16 kHz mono PCM16 WAV") { if (!working && !recording) picker.launch(arrayOf("audio/*", "application/octet-stream")) }
        text("All recognition is local. Automatic finishing uses Silero after speech and a one-second pause. Without its downloaded model, tap Finish manually. Models stay warm between turns when memory permits.", 16f)
    }
    private fun permissionsGranted(forGlasses: Boolean = true): Boolean {
        val required = if (forGlasses) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.CAMERA)
            else arrayOf(Manifest.permission.RECORD_AUDIO)
        if (required.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) return true
        requestPermissions(required, 30); status.text = "Grant permissions, then tap the button again."; return false
    }
    private fun report(message: String, ticket: Int) { runOnUiThread { if (ticket == generation && !isDestroyed) status.text = message } }
    private fun beginWork(block: suspend () -> Unit) {
        if (working || recording) return
        working = true; val ticket = ++generation
        publishWatch()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        work = lifecycleScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (ticket == generation) status.text = e.message ?: "Speech operation failed" }
            catch (e: OutOfMemoryError) { if (ticket == generation) status.text = "Not enough memory for Parakeet. Close other apps and try a shorter turn." }
            catch (e: UnsatisfiedLinkError) { if (ticket == generation) status.text = "Speech runtime unavailable on this device: ${e.message}" }
            finally { if (ticket == generation) { working = false;publishWatch();window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } }
        }
    }
    private fun startTurn(phone: Boolean) {
        if (working || recording || !permissionsGranted(!phone)) return
        if (!models.installed()) { status.text = "Download the speech model first."; return }
        recording = true; generation++; val ticket = generation
        publishWatch()
        val buffer = PcmTurn(); turn = buffer
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text = if (phone) "Recording PHONE microphone baseline…" else "Connecting glasses PCM…"
        work = lifecycleScope.launch {
            try {
                if(autoFinish && VadModels.isInstalled(applicationContext)) endpoint=SpeechEndpoint.create(applicationContext)
                if (phone) startPhone(buffer, ticket)
                else glasses.start(buffer, endpoint, { if (ticket == generation) finishTurn() }, { message -> if (ticket == generation) { cancelWork(); status.text = message } })
            } catch (e: Exception) { if (e !is CancellationException && ticket == generation) { cancelWork(); status.text = e.message } }
            catch (e: UnsatisfiedLinkError) { if(ticket==generation){cancelWork();status.text="Speech detection runtime unavailable: ${e.message}"} }
            catch (e: OutOfMemoryError) { if(ticket==generation){cancelWork();status.text="Not enough memory to begin capture. Close other apps and retry."} }
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
                    val ended = endpoint?.acceptPcm16(bytes) == true
                    if (full || ended) { withContext(Dispatchers.Main) { if (ticket == generation) finishTurn() }; break }
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
            previous?.join(); microphone?.cancelAndJoin(); microphone = null; glasses.stop();endpoint?.close();endpoint=null
            recognize(captured.finish())
        }
    }
    private suspend fun recognize(samples: FloatArray) {
        val started = SystemClock.elapsedRealtime()
        val ticket = generation
        val text = withContext(Dispatchers.IO) { models.recognize(samples) { report(it, ticket) } }
        currentCoroutineContext().ensureActive()
        setResult(RESULT_OK, Intent().putExtra("transcript",text).putExtra("language",language).putExtra("watchArmed",watchArmed).putExtra("elapsedMs",SystemClock.elapsedRealtime()-started))
        finish()
    }
    private fun cancelWork() {
        generation++; recording = false; working = false; turn = null
        work?.cancel(); microphone?.cancel(); timer?.cancel()
        // Serialize teardown before accepting a new capture session.
        working = true
        publishWatch()
        val ticket = generation
        lifecycleScope.launch {
            try { microphone?.join(); glasses.stop();endpoint?.close();endpoint=null }
            finally { if (ticket == generation) { working = false;publishWatch() } }
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun publishWatch() { if(::watch.isInitialized) watch.state(watchArmed,recording,working,false,
        if(watchArmed) "${if(phoneSource) "Phone baseline" else "Glasses"} • ${if(recording) "Listening" else if(working) "Processing" else "Ready"}" else "Enable watch controls on the phone.") }
    private fun watchCommand(action: String): String? {
        if(action=="cancel") { cancelWork();status.text="Canceled from watch";return null }
        if(action=="finish") { if(!recording)return "No turn is recording";finishTurn();return null }
        if(action!="listen_lt" && action!="reply_en")return "Return to the translation screen for playback"
        if(recording || working)return "Finish or cancel the current turn first"
        if(!models.installed())return "Download the speech model on your phone first"
        language=if(action=="reply_en") "en" else "lt"
        heading.text=if(language=="en") "Speak English → Lithuanian" else "Listen: Lithuanian → English"
        startTurn(phoneSource);return if(recording) null else "Check microphone permissions on the phone"
    }
    override fun onResume() {
        super.onResume();if(::watch.isInitialized){publishWatch();watch.start()}
        if(intent.getBooleanExtra("autoStart",false)) { intent.removeExtra("autoStart");window.decorView.post { if(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) startTurn(phoneSource) } }
    }
    override fun onPause() { if(::watch.isInitialized)watch.stop();super.onPause() }
    override fun onNewIntent(incoming: Intent) { super.onNewIntent(incoming) /* Keep selected language and result relationship on Meta callback reuse. */ }
    override fun onSaveInstanceState(state: Bundle) { state.putString("language",language);super.onSaveInstanceState(state) }
    override fun onStop() { super.onStop(); if (::glasses.isInitialized) { cancelWork(); status.text = "Paused. Tap a button to begin again." } }
    override fun onDestroy() { if (::glasses.isInitialized) glasses.close();endpoint?.close();endpoint=null;super.onDestroy() }
}
