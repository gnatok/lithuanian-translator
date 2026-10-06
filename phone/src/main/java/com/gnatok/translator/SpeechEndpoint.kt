package com.gnatok.translator

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Optional Silero model, installed explicitly with the speech pack; never downloads during capture. */
internal object VadModels {
    private const val SIZE = 643854L
    private const val SHA = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"
    private const val URL_STRING = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
    private val installLock = Mutex()
    private fun file(context: Context) = File(context.noBackupFilesDir, "silero-vad.onnx")
    fun isInstalled(context: Context) = file(context).length() == SIZE
    private suspend fun valid(file: File): Boolean {
        if (file.length() != SIZE) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { source ->
            val buffer = ByteArray(65536)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = source.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == SHA
    }
    private fun requireWifi(context: Context) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
        check(caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true && !manager.isActiveNetworkMetered) {
            "Connect to unmetered Wi-Fi to download speech detection"
        }
    }
    suspend fun install(context: Context) = withContext(Dispatchers.IO) {
        installLock.withLock {
            val target = file(context)
            if (valid(target)) return@withLock
            requireWifi(context)
            val pending = File(target.path + ".part")
            val connection = URL(URL_STRING).openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            try {
                check(connection.responseCode == 200) { "Speech detection server returned ${connection.responseCode}" }
                var total = 0L
                connection.inputStream.use { source -> pending.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        requireWifi(context)
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= SIZE) { "Speech detection download exceeds expected size" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                } }
                check(valid(pending)) { "Speech detection model integrity check failed" }
                check(pending.renameTo(target)) { "Could not finalize speech detection model" }
            } finally {
                connection.disconnect()
                pending.delete()
            }
        }
    }
    suspend fun verifiedPath(context: Context): String = withContext(Dispatchers.IO) {
        val target = file(context)
        check(valid(target)) { "Download the speech detection model first" }
        target.path
    }
}

/** One detector per turn. An endpoint requires real speech followed by one second of silence.
 * PCM remains untouched: PcmTurn owns the complete recording, including the trailing silence.
 * Call accept off the main thread. Never share one instance between separate capture sources.
 */
internal class SpeechEndpoint private constructor(private val vad: Vad) : Closeable {
    private val window = FloatArray(512)
    private var used = 0
    private var ended = false
    private var closed = false

    @Synchronized
    fun accept(samples: FloatArray): Boolean {
        check(!closed) { "Speech detector is closed" }
        if (ended) return true
        for (sample in samples) {
            require(sample.isFinite()) { "Invalid speech sample" }
            window[used++] = sample
            if (used == window.size) {
                vad.acceptWaveform(window)
                used = 0
                // Silero emits a completed segment only after minSpeechDuration and minSilenceDuration.
                if (!vad.empty()) {
                    ended = true
                    vad.clear()
                    return true
                }
            }
        }
        return false
    }

    @Synchronized
    fun acceptPcm16(input: ByteBuffer): Boolean {
        val bytes = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.remaining() % 2 == 0) { "Incomplete PCM16 sample" }
        return accept(FloatArray(bytes.remaining() / 2) { bytes.short / 32768f })
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            vad.release()
        }
    }

    companion object {
        suspend fun create(context: Context): SpeechEndpoint {
            var created: SpeechEndpoint? = null
            try {
                return withContext(Dispatchers.IO) {
                    val model = VadModels.verifiedPath(context)
                    currentCoroutineContext().ensureActive()
                    SpeechEndpoint(Vad(config = VadModelConfig(
                        sileroVadModelConfig = SileroVadModelConfig(
                            model = model, threshold = 0.5f, minSpeechDuration = 0.25f,
                            minSilenceDuration = 1.0f, windowSize = 512,
                            // PcmTurn enforces its own 20-second limit; do not split a speaking turn here.
                            maxSpeechDuration = 30.0f
                        ), sampleRate = 16000, numThreads = 1, provider = "cpu"
                    ))).also { created = it }
                }
            } catch (error: Throwable) {
                // withContext can discard a completed native allocation when the caller cancels.
                created?.close()
                throw error
            }
        }
    }
}
