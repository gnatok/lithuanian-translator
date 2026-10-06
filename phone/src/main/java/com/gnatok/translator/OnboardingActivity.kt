package com.gnatok.translator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.*
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Setup reports model and permission state; only an actual session can verify glasses PCM. */
class OnboardingActivity : ComponentActivity() {
    private lateinit var glasses: GlassesCapture
    private lateinit var models: SpeechModels
    private lateinit var permissions: TextView
    private lateinit var registration: TextView
    private lateinit var packStatus: TextView
    private lateinit var progress: TextView
    private lateinit var start: Button
    private lateinit var download: Button
    private lateinit var cancelDownload: Button
    private lateinit var voiceStatus: TextView
    private var speechEngine: TextToSpeech? = null
    private var speechEngineReady = false
    private var offlineVoiceReady = false
    private var work: Job? = null
    private var refresh: Job? = null
    private var downloading = false
    private var metaInitialized = false
    private val runtimePermissions = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_CONNECT)
    private val translators by lazy {
        listOf(
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.LITHUANIAN).setTargetLanguage(TranslateLanguage.ENGLISH).build()),
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.LITHUANIAN).build())
        )
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSharedPreferences("onboarding", MODE_PRIVATE).edit().putBoolean("seen", true).apply()
        DebugLog.event("onboarding.open", "setup screen")
        models = SpeechModels(applicationContext)
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(28))
            setBackgroundColor(Color.rgb(244,247,242))
        }
        scroll.addView(column); setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        fun text(value: String, size: Float = 17f): TextView = TextView(this).also {
            it.text=value;it.textSize=size;it.setTextColor(Color.rgb(17,44,43));it.setPadding(0,dp(10),0,dp(8));column.addView(it)
        }
        fun button(value: String, action: () -> Unit): Button = Button(this).also {
            it.text=value;it.isAllCaps=false;it.minHeight=dp(54);it.setOnClickListener { action() };column.addView(it,LinearLayout.LayoutParams(-1,-2))
        }
        text("Set up your translator",30f).setTypeface(null,Typeface.BOLD)
        text("Three steps, once. After downloading, speech recognition and translation work on your phone without internet.")
        text("1 · Allow access",22f).setTypeface(null,Typeface.BOLD)
        text("Microphone and nearby-device access connect audio. Experimental Meta PCM also requires camera access; video is discarded.")
        permissions=text("Checking permissions…")
        button("Allow microphone, camera & nearby devices") { requestPermissions(runtimePermissions,701);DebugLog.event("onboarding.android_permissions", "requested") }
        text("2 · Connect Meta glasses",22f).setTypeface(null,Typeface.BOLD)
        text("Pair HSTN in Meta AI and enable Developer Mode there. Registration requires internet. This experimental capture mode depends on your glasses firmware and Meta access.")
        registration=text("Meta registration not checked yet.")
        glasses=GlassesCapture(this) { message -> registration.text=message;checkReadiness() }
        button("Connect to Meta AI") { metaAction { glasses.register() } }
        button("Grant glasses camera & microphone access") { metaAction { glasses.permissions() } }
        text("3 · Download offline components",22f).setTypeface(null,Typeface.BOLD)
        text("About 670 MB for speech recognition, plus translation and speech detection. Use unmetered Wi-Fi and keep this screen open. Completed verified files are reused after interruption.")
        packStatus=text("Checking downloaded components…")
        progress=text("").apply { accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
        download=button("Download needed components on Wi-Fi") { install() }
        cancelDownload=button("Cancel download") { work?.cancel();DebugLog.event("onboarding.download.cancel", "user") }.apply { isEnabled=false }
        voiceStatus=text("English playback: checking installed offline voice…")
        text("An offline English voice is required to hear translations in your glasses. Your Android speech engine downloads this separately.",15f)
        button("Install / manage English voice") {
            try {
                val intent=Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                speechEngine?.defaultEngine?.let { intent.setPackage(it) }
                startActivity(intent)
                DebugLog.event("onboarding.voice.settings", "open engine voice installation")
            } catch(error:Exception) {
                DebugLog.error("onboarding.voice.settings",error)
                try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                catch(fallback:Exception) { voiceStatus.text="Open Android Settings → Text-to-speech → install English voice data.";DebugLog.error("onboarding.voice.settings.fallback",fallback) }
            }
        }
        button("Check setup again") { checkReadiness() }
        start=button("Continue to translation") {
            DebugLog.event("onboarding.continue", "open conversation")
            startActivity(Intent().setClassName(this,"com.gnatok.translator.ConversationActivity"));finish()
        }.apply { isEnabled=false;minHeight=dp(66);textSize=20f }
        text("Setup checks permissions and installed files. It does not prove audio is arriving from the glasses; the translation screen verifies the live stream. Keep the app visible during use.",15f)
        button("Back to home") { finish() }
        button("Diagnostics / export report") { startActivity(Intent(this,DebugActivity::class.java)) }
        initializeMeta()
        speechEngine=TextToSpeech(applicationContext) { status -> runOnUiThread {
            if(!isDestroyed) {
                speechEngineReady=status==TextToSpeech.SUCCESS
                DebugLog.event("onboarding.voice.engine", "result=$status ready=$speechEngineReady")
                checkReadiness()
            }
        } }
        checkReadiness()
    }
    private fun dp(value: Int) = (value*resources.displayMetrics.density).toInt()
    private fun initializeMeta() {
        if(metaInitialized)return
        if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)return
        try { glasses.initialize();metaInitialized=true }
        catch(e:Exception) { registration.text="Meta setup unavailable: ${e.message}";DebugLog.error("onboarding.meta.initialize",e) }
    }
    private fun metaAction(action: () -> Unit) {
        if(runtimePermissions.any { checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED }) {
            permissions.text="Allow Android permissions in step 1, then retry this step.";return
        }
        try { initializeMeta();action() }
        catch(e:Exception) { registration.text="Meta action failed: ${e.message}";DebugLog.error("onboarding.meta.action",e) }
    }
    private fun checkReadiness() {
        if(!::start.isInitialized)return
        refreshVoice()
        refresh?.cancel()
        refresh=lifecycleScope.launch {
            val androidReady=runtimePermissions.all { checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED }
            var metaReady=false
            if(metaInitialized) {
                try { metaReady=listOf(Permission.CAMERA,Permission.MICROPHONE).all { Wearables.checkPermissionStatus(it).getOrDefault(PermissionStatus.Denied)==PermissionStatus.Granted } }
                catch(e:Exception) { DebugLog.error("onboarding.meta.check",e) }
            }
            permissions.text="Android access: ${if(androidReady) "granted" else "needed"}\nGlasses camera + microphone access: ${if(metaReady) "granted" else "not yet confirmed"}"
            try {
                val languageReady=RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java).awaitResult().any { it.language==TranslateLanguage.LITHUANIAN }
                val speechReady=models.installed()
                val vadReady=VadModels.isInstalled(applicationContext)
                packStatus.text="Speech recognition: ${if(speechReady) "files installed; verified before use" else "download needed"}\nTranslation: ${if(languageReady) "installed" else "download needed"}\nSpeech detection: ${if(vadReady) "installed" else "download needed"}"
                start.isEnabled=androidReady&&metaReady&&speechReady&&languageReady&&vadReady&&offlineVoiceReady&&!downloading
                DebugLog.event("onboarding.readiness", "android=$androidReady metaPermissions=$metaReady speech=$speechReady translation=$languageReady vad=$vadReady offlineEnglishVoice=$offlineVoiceReady")
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { packStatus.text="Could not check language pack: ${e.message}";start.isEnabled=false;DebugLog.error("onboarding.readiness.error",e) }
        }
    }
    private fun refreshVoice() {
        try {
            val voice=if(speechEngineReady) speechEngine?.voices?.firstOrNull {
                it.locale.language=="en" && !it.isNetworkConnectionRequired && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            } else null
            offlineVoiceReady=voice!=null
            voiceStatus.text=when {
                voice!=null -> "English playback: offline voice installed (${voice.locale.displayName})"
                !speechEngineReady -> "English playback: speech engine not ready. Check setup again, or install an English voice."
                else -> "English playback: download an offline English voice using the button below."
            }
            DebugLog.event("onboarding.voice.readiness", "engineReady=$speechEngineReady offlineVoice=$offlineVoiceReady voice=${voice?.name ?: "none"}")
        } catch(error:Exception) { offlineVoiceReady=false;voiceStatus.text="Cannot check English voice. Open voice settings, then check setup again.";DebugLog.error("onboarding.voice.readiness",error) }
    }
    private fun install() {
        if(downloading)return
        downloading=true;download.isEnabled=false;cancelDownload.isEnabled=true;start.isEnabled=false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        DebugLog.event("onboarding.download.start", "all required components")
        work=lifecycleScope.launch {
            try {
                progress.text="Downloading translation pack on Wi-Fi…"
                val conditions=DownloadConditions.Builder().requireWifi().build()
                translators.forEach { it.downloadModelIfNeeded(conditions).awaitResult() }
                withContext(Dispatchers.IO) { models.install { message -> runOnUiThread { if(downloading)progress.text=message } } }
                progress.text="Downloading speech detection…"
                VadModels.install(applicationContext)
                progress.text="Downloads complete. You can check glasses access above."
                DebugLog.event("onboarding.download.complete", "all components installed")
            } catch(e:CancellationException) { progress.text="Setup stopped. Completed files are kept; a translation-pack request may still finish in the background.";throw e }
            catch(e:Exception) { progress.text="Download failed: ${e.message}";DebugLog.error("onboarding.download.error",e) }
            finally {
                downloading=false;download.isEnabled=true;cancelDownload.isEnabled=false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);checkReadiness()
            }
        }
    }
    override fun onResume() { super.onResume();if(::start.isInitialized)checkReadiness() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent);DebugLog.event("onboarding.meta.callback", "returned to setup");checkReadiness() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);initializeMeta();checkReadiness()
    }
    override fun onDestroy() { work?.cancel();refresh?.cancel();speechEngine?.shutdown();if(::glasses.isInitialized)glasses.close();translators.forEach { it.close() };super.onDestroy() }
    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { if(continuation.isActive)continuation.resume(it) }
        addOnFailureListener { if(continuation.isActive)continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.cancel() }
    }
}
