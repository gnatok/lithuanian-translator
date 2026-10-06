package com.gnatok.translator

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Pinned model bytes stay in private, non-backed-up storage. Call only off the UI thread. */
internal class SpeechModels(private val context: Context) {
    private val folder = File(context.noBackupFilesDir, "parakeet-v3-int8").apply { mkdirs() }
    private data class Part(val name: String, val bytes: Long, val sha256: String)
    private val parts = listOf(
        Part("encoder.int8.onnx", 652184281, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
        Part("decoder.int8.onnx", 11845275, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
        Part("joiner.int8.onnx", 6355277, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
        Part("tokens.txt", 93939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d")
    )
    fun installed() = parts.all { File(folder, it.name).length() == it.bytes }
    private suspend fun valid(part: Part): Boolean {
        val file = File(folder, part.name)
        if (file.length() != part.bytes) return false
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val bytes = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = stream.read(bytes); if (read < 0) break
                hash.update(bytes, 0, read)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) } == part.sha256
    }
    private fun requireWifi() {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
        check(caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true && !manager.isActiveNetworkMetered) {
            "Connect to unmetered Wi-Fi to download the speech model"
        }
    }
    suspend fun install(progress: (String) -> Unit) {
        for (part in parts) {
            if (valid(part)) continue
            requireWifi()
            check(folder.usableSpace > part.bytes + 100_000_000) { "Not enough free storage for ${part.name}" }
            val pending = File(folder, part.name + ".part")
            val url = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/2bda32ec70b097a55adaa07d9a7173915b43cc78/${part.name}"
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30000; connection.readTimeout = 30000
            try {
                check(connection.responseCode == 200) { "Model server returned ${connection.responseCode}" }
                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L; var lastPercent = -1L
                connection.inputStream.use { source -> pending.outputStream().use { target ->
                    val bytes = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive(); requireWifi()
                        val read = source.read(bytes); if (read < 0) break
                        written += read
                        check(written <= part.bytes) { "Model download exceeds expected size" }
                        target.write(bytes, 0, read); digest.update(bytes, 0, read)
                        val percent = written * 100 / part.bytes
                        if (percent != lastPercent) { progress("Downloading ${part.name}: $percent%"); lastPercent = percent }
                    }
                    target.fd.sync()
                } }
                check(written == part.bytes && digest.digest().joinToString("") { "%02x".format(it) } == part.sha256) { "Model integrity check failed" }
                check(pending.renameTo(File(folder, part.name))) { "Could not finalize model file" }
            } finally { connection.disconnect(); pending.delete() }
        }
    }
    suspend fun recognize(samples: FloatArray, progress: (String) -> Unit): String {
        progress("Verifying speech model…")
        check(installed()) { "Download the speech model first" }
        for (part in parts) check(valid(part)) { "Model integrity check failed; download again" }
        currentCoroutineContext().ensureActive()
        progress("Loading Parakeet on the phone…")
        val config = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = File(folder, "encoder.int8.onnx").path,
                decoder = File(folder, "decoder.int8.onnx").path,
                joiner = File(folder, "joiner.int8.onnx").path),
            tokens = File(folder, "tokens.txt").path, numThreads = 2, provider = "cpu", modelType = "nemo_transducer"
        ))
        val recognizer = OfflineRecognizer(config = config)
        try {
            currentCoroutineContext().ensureActive()
            progress("Recognizing locally…")
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, 16000)
                recognizer.decode(stream)
                currentCoroutineContext().ensureActive()
                return recognizer.getResult(stream).text.trim().also { check(it.isNotBlank()) { "No speech recognized; try again" } }
            } finally { stream.release() }
        } finally { recognizer.release() }
    }
}
