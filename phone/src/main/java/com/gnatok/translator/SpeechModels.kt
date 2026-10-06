package com.gnatok.translator

import android.content.Context
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.SystemClock
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Pinned model bytes stay in private, non-backed-up storage. Call only off the UI thread. */
internal class SpeechModels(context: Context) {
    private val context = context.applicationContext
    companion object {
        // Native decode/release cannot overlap, including after a canceled activity turn.
        private val operation = Mutex()
        private val maintenance = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var cached: OfflineRecognizer? = null
        private var verifiedIdentity: String? = null
        private var cachedIdentity: String? = null
        private var callbacksRegistered = false
        suspend fun releaseCached() = operation.withLock { releaseLocked() }
        private fun releaseLocked() {
            val previous = cached
            cached = null; cachedIdentity = null
            if (previous != null) DebugLog.event("asr.cache.release", "Native recognizer released")
            previous?.release()
        }
        @Synchronized private fun registerCallbacks(context: Context) {
            if (callbacksRegistered) return
            context.registerComponentCallbacks(object : ComponentCallbacks2 {
                override fun onConfigurationChanged(configuration: Configuration) = Unit
                override fun onLowMemory() { maintenance.launch { releaseCached() } }
                override fun onTrimMemory(level: Int) {
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
                        maintenance.launch { releaseCached() }
                }
            })
            callbacksRegistered = true
        }
    }
    init { registerCallbacks(this.context) }
    private val folder = File(context.noBackupFilesDir, "parakeet-v3-int8").apply { mkdirs() }
    private data class Part(val name: String, val bytes: Long, val sha256: String)
    private val parts = listOf(
        Part("encoder.int8.onnx", 652184281, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
        Part("decoder.int8.onnx", 11845275, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
        Part("joiner.int8.onnx", 6355277, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
        Part("tokens.txt", 93939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d")
    )
    fun installed(): Boolean {
        val missing = parts.filter { File(folder, it.name).length() != it.bytes }
        DebugLog.event("asr.models.readiness", "ready=${missing.isEmpty()} missingOrWrongSize=${missing.joinToString { it.name }}")
        return missing.isEmpty()
    }
    private fun identity() = folder.absolutePath + parts.joinToString { part ->
        val file = File(folder, part.name)
        "${part.name}:${file.length()}:${file.lastModified()}"
    }
    private suspend fun valid(part: Part): Boolean {
        val file = File(folder, part.name)
        if (file.length() != part.bytes) {
            DebugLog.event("asr.model.size", "file=${part.name} actualBytes=${file.length()} expectedBytes=${part.bytes}")
            return false
        }
        val started = SystemClock.elapsedRealtime()
        DebugLog.event("asr.model.verify.start", "file=${part.name} bytes=${part.bytes}")
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val bytes = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = stream.read(bytes); if (read < 0) break
                hash.update(bytes, 0, read)
            }
        }
        val valid = hash.digest().joinToString("") { "%02x".format(it) } == part.sha256
        DebugLog.event("asr.model.verify.end", "file=${part.name} valid=$valid elapsedMs=${SystemClock.elapsedRealtime() - started}")
        return valid
    }
    private fun requireWifi() {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
        check(caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true && !manager.isActiveNetworkMetered) {
            "Connect to unmetered Wi-Fi to download the speech model"
        }
    }
    suspend fun install(progress: (String) -> Unit) = operation.withLock {
        DebugLog.event("asr.install.start", "freeBytes=${folder.usableSpace}")
        try {
        for (part in parts) {
            if (valid(part)) continue
            releaseLocked(); verifiedIdentity = null
            requireWifi()
            check(folder.usableSpace > part.bytes + 100_000_000) { "Not enough free storage for ${part.name}" }
            val pending = File(folder, part.name + ".part")
            val url = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/2bda32ec70b097a55adaa07d9a7173915b43cc78/${part.name}"
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30000; connection.readTimeout = 30000
            val downloadStarted = SystemClock.elapsedRealtime()
            DebugLog.event("asr.download.start", "file=${part.name} expectedBytes=${part.bytes}")
            try {
                DebugLog.event("asr.download.http", "file=${part.name} status=${connection.responseCode}")
                check(connection.responseCode == 200) { "Model server returned ${connection.responseCode}" }
                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L; var lastPercent = -1L; var lastLoggedBucket = -1L
                connection.inputStream.use { source -> pending.outputStream().use { target ->
                    val bytes = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive(); requireWifi()
                        val read = source.read(bytes); if (read < 0) break
                        written += read
                        check(written <= part.bytes) { "Model download exceeds expected size" }
                        target.write(bytes, 0, read); digest.update(bytes, 0, read)
                        val percent = written * 100 / part.bytes
                        if (percent / 10 != lastLoggedBucket) {
                            lastLoggedBucket = percent / 10
                            DebugLog.event("asr.download.progress", "file=${part.name} percent=${lastLoggedBucket * 10} bytes=$written elapsedMs=${SystemClock.elapsedRealtime() - downloadStarted}")
                        }
                        if (percent != lastPercent) { progress("Downloading ${part.name}: $percent%"); lastPercent = percent }
                    }
                    target.fd.sync()
                } }
                check(written == part.bytes && digest.digest().joinToString("") { "%02x".format(it) } == part.sha256) { "Model integrity check failed" }
                check(pending.renameTo(File(folder, part.name))) { "Could not finalize model file" }
                DebugLog.event("asr.download.success", "file=${part.name} bytes=$written sha256Verified=true elapsedMs=${SystemClock.elapsedRealtime() - downloadStarted}")
            } finally { connection.disconnect(); pending.delete() }
        }
        verifiedIdentity = identity()
        DebugLog.event("asr.install.ready", "All pinned model files verified")
        } catch (e: Throwable) { DebugLog.error("asr.install.failed", e); throw e }
    }
    suspend fun recognize(samples: FloatArray, progress: (String) -> Unit): String = operation.withLock {
        val started = SystemClock.elapsedRealtime()
        DebugLog.event("asr.recognize.start", "samples=${samples.size} sampleRate=16000 audioMs=${samples.size * 1000L / 16000}")
        try {
        check(installed()) { "Download the speech model first" }
        val currentIdentity = identity()
        if (verifiedIdentity != currentIdentity) {
            releaseLocked()
            progress("Verifying speech model…")
            for (part in parts) check(valid(part)) { "Model integrity check failed; download again" }
            verifiedIdentity = currentIdentity
        }
        currentCoroutineContext().ensureActive()
        val reused = cached != null && cachedIdentity == currentIdentity
        DebugLog.event("asr.cache", "reused=$reused")
        if (!reused) {
            releaseLocked()
            progress("Loading Parakeet on the phone…")
            DebugLog.event("asr.load.start", "provider=cpu threads=2 model=parakeet-v3-int8")
            val config = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(folder, "encoder.int8.onnx").path,
                    decoder = File(folder, "decoder.int8.onnx").path,
                    joiner = File(folder, "joiner.int8.onnx").path),
                tokens = File(folder, "tokens.txt").path, numThreads = 2, provider = "cpu", modelType = "nemo_transducer"
            ))
            cached = OfflineRecognizer(config = config)
            cachedIdentity = currentIdentity
            DebugLog.event("asr.load.ready", "elapsedMs=${SystemClock.elapsedRealtime() - started}")
        }
        val recognizer = checkNotNull(cached)
        val prepared = SystemClock.elapsedRealtime()
        try {
            currentCoroutineContext().ensureActive()
            progress("Recognizing locally…")
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, 16000)
                DebugLog.event("asr.decode.start", "samples=${samples.size}")
                recognizer.decode(stream)
                currentCoroutineContext().ensureActive()
                val result = recognizer.getResult(stream).text.trim()
                DebugLog.event("asr.decode.end", "resultCharacters=${result.length} elapsedMs=${SystemClock.elapsedRealtime() - prepared}")
                check(result.isNotBlank()) { "No speech recognized; try again" }
                progress("Speech ready • ${if (reused) "warm" else "loaded"} ${prepared - started} ms • decode ${SystemClock.elapsedRealtime() - prepared} ms")
                result
            } finally { stream.release() }
        } catch (e: CancellationException) { throw e }
        catch (e: Throwable) { releaseLocked(); throw e }
        } catch (e: Throwable) { DebugLog.error("asr.recognize.failed", e); throw e }
    }
}
