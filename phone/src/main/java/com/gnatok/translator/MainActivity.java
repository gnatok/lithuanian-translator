package com.gnatok.translator;

import android.Manifest;
import android.app.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioDeviceInfo;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import com.gnatok.translator.core.TurnGate;
import com.google.android.gms.wearable.*;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.*;
import java.util.List;

public final class MainActivity extends Activity {
    private Translator ltToEn, enToLt;
    private boolean listening = true, ready, flipped;
    private float font = 38;
    private EditText input;
    private TextView status, output, outputLabel, diagnostic;
    private Button direction;
    private AudioProbe probe;
    private final TurnGate gate = new TurnGate();
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Color.rgb(17, 44, 43));
        ltToEn = translator(TranslateLanguage.LITHUANIAN, TranslateLanguage.ENGLISH);
        enToLt = translator(TranslateLanguage.ENGLISH, TranslateLanguage.LITHUANIAN);
        probe = new AudioProbe(this);
        if (saved != null) { listening = saved.getBoolean("listening", true); font = saved.getFloat("font", 38); flipped = saved.getBoolean("flipped"); }
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(32), dp(24), dp(32)); root.setBackgroundColor(Color.rgb(244, 247, 242));
        scroll.addView(root); setContentView(scroll);
        // Respect status/navigation bars on Android 15+ edge-to-edge.
        scroll.setOnApplyWindowInsetsListener((v, insets) -> { android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()); v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets; });
        label(root, "LITHUANIAN ↔ ENGLISH", 14);
        label(root, "A conversation,\nin two languages.", 30);
        status = label(root, "Checking offline language pack…", 16);
        button(root, "Download offline language pack (Wi-Fi)", this::download);
        direction = button(root, "", () -> { listening = !listening; gate.next(); input.setText(""); refreshDirection(); status.setText(ready ? "Ready for typed translation" : "Download the language pack first"); });
        input = new EditText(this); input.setMinLines(2); input.setTextSize(22); input.setGravity(Gravity.TOP); root.addView(input);
        button(root, "Translate text on this phone", this::translate);
        button(root, "Speak using glasses / phone / WAV", () -> {
            if (!ready) { status.setText("Download the translation language pack first"); return; }
            gate.next(); probe.stop();
            startActivityForResult(new Intent(this, SpeechActivity.class).putExtra("language", listening ? "lt" : "en"), 101);
        });
        outputLabel = label(root, saved == null ? "Translation" : saved.getString("label", "Translation"), 16);
        output = label(root, saved == null ? "Your translation will appear here." : saved.getString("output", ""), font);
        output.setTextIsSelectable(true); output.setPadding(0, dp(20), 0, dp(20)); output.setRotation(flipped ? 180 : 0);
        LinearLayout controls = new LinearLayout(this); root.addView(controls);
        button(controls, "A−", () -> { font = Math.max(24, font - 6); output.setTextSize(font); });
        button(controls, "A+", () -> { font = Math.min(80, font + 6); output.setTextSize(font); });
        button(controls, "Flip text", () -> { flipped = !flipped; output.setRotation(flipped ? 180 : 0); });
        label(root, "Glasses microphone check", 24);
        label(root, "Pair HSTN in Android Bluetooth settings. This 15-second check shows the actual input route and audio level. It does not save audio or transcribe speech.", 16);
        diagnostic = label(root, "Not recording", 16);
        button(root, "Choose headset and test microphone", this::chooseHeadset);
        button(root, "Stop microphone", () -> { probe.stop(); diagnostic.setText("Stopped. Audio discarded."); });
        label(root, "POC 0.2 • Offline Parakeet recognition and experimental Meta PCM capture. Device compatibility and speech quality need testing. Playback and automatic voice detection are next.", 14);
        if (saved != null) input.setText(saved.getString("input", ""));
        refreshDirection(); checkModels();
    }
    private Translator translator(String source, String target) { return Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build()); }
    private void refreshDirection() {
        direction.setText(listening ? "LISTEN • Lithuanian → English  ⇄" : "REPLY • English → Lithuanian  ⇄");
        input.setHint(listening ? "Type or paste Lithuanian" : "Type your English reply");
        // Keep the previous completed translation and its original language label visible.
    }
    private void checkModels() {
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel.class).addOnSuccessListener(this, models -> {
            ready = models.stream().anyMatch(m -> m.getLanguage().equals(TranslateLanguage.LITHUANIAN));
            status.setText(ready ? "Offline pack ready • translation stays on this phone" : "Download the Lithuanian pack before translating");
        }).addOnFailureListener(this, e -> status.setText("Cannot check models: " + e.getMessage()));
    }
    private void download() {
        status.setText("Downloading language pack over Wi-Fi…");
        DownloadConditions conditions = new DownloadConditions.Builder().requireWifi().build();
        Tasks.whenAll(ltToEn.downloadModelIfNeeded(conditions), enToLt.downloadModelIfNeeded(conditions))
            .addOnSuccessListener(this, unused -> checkModels())
            .addOnFailureListener(this, e -> status.setText("Download failed: " + e.getMessage()));
    }
    private void translate() {
        String text = input.getText().toString().trim();
        if (!ready) { status.setText("Download the offline pack first"); return; }
        if (text.isEmpty()) { input.setError("Enter a phrase"); return; }
        long ticket = gate.next(); boolean toEnglish = listening;
        status.setText("Translating locally…");
        (toEnglish ? ltToEn : enToLt).translate(text).addOnSuccessListener(this, result -> {
            if (!gate.accepts(ticket)) return;
            output.setText(result); outputLabel.setText(toEnglish ? "English • completed translation" : "Lietuvių • completed translation");
            status.setText("Translated on this phone");
            PutDataMapRequest request = PutDataMapRequest.create("/translation/latest");
            request.getDataMap().putString("text", result);
            request.getDataMap().putString("language", toEnglish ? "English" : "Lietuvių");
            request.getDataMap().putLong("time", System.currentTimeMillis());
            Wearable.getDataClient(this).putDataItem(request.asPutDataRequest().setUrgent())
                .addOnFailureListener(this, e -> status.setText("Translated locally • watch sync unavailable"));
        }).addOnFailureListener(this, e -> { if (gate.accepts(ticket)) status.setText("Translation failed: " + e.getMessage()); });
    }
    private void chooseHeadset() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT}, 1);
            diagnostic.setText("Grant microphone and nearby-device access, then tap the test button again."); return;
        }
        try {
            List<AudioDeviceInfo> devices = probe.devices();
            if (devices.isEmpty()) { diagnostic.setText("No Bluetooth communication headset found. Pair/connect HSTN, then try again."); return; }
            String[] labels = devices.stream().map(d -> d.getProductName() + " (device " + d.getId() + ")").toArray(String[]::new);
            new AlertDialog.Builder(this).setTitle("Select your glasses").setItems(labels, (dialog, which) -> probe.start(devices.get(which), diagnostic::setText)).show();
        } catch (RuntimeException e) { diagnostic.setText("Cannot list headset routes: " + e.getMessage()); }
    }
    private TextView label(LinearLayout root, String value, float size) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(Color.rgb(17, 44, 43)); view.setPadding(0, dp(8), 0, dp(8)); root.addView(view); return view; }
    private Button button(LinearLayout root, String title, Runnable action) { Button view = new Button(this); view.setText(title); view.setAllCaps(false); view.setMinHeight(dp(48)); view.setOnClickListener(v -> action.run()); root.addView(view); return view; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 101 && result == RESULT_OK && data != null) {
            String transcript = data.getStringExtra("transcript");
            if (transcript != null && !transcript.trim().isEmpty()) { input.setText(transcript); translate(); }
        }
    }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); state.putBoolean("listening", listening); state.putFloat("font", font); state.putBoolean("flipped", flipped); state.putString("input", input.getText().toString()); state.putString("output", output.getText().toString()); state.putString("label", outputLabel.getText().toString()); }
    @Override protected void onStop() { super.onStop(); probe.stop(); diagnostic.setText("Not recording"); }
    @Override protected void onDestroy() { gate.next(); probe.close(); ltToEn.close(); enToLt.close(); super.onDestroy(); }
}
