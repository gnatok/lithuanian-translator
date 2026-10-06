package com.gnatok.translator;

import android.Manifest;
import android.app.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
    private TextView status, output, outputLabel, diagnostic, sourcePreview;
    private Button listenButton, replyButton, speakButton, playbackButton, presentButton;
    private LinearLayout workspace, conversation, resultPanel;
    private EnglishPlayback playback;
    private boolean resultEnglish, hasResult, speechResult;
    private Dialog presentation;
    private AudioProbe probe;
    private WatchBridge watch;
    private boolean watchArmed, translating, launchingSpeech;
    private Switch watchToggle;
    private final TurnGate gate = new TurnGate();
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        DebugLog.event("phone.create", "restored=" + (saved != null));
        getWindow().setStatusBarColor(Color.rgb(17, 44, 43));
        ltToEn = translator(TranslateLanguage.LITHUANIAN, TranslateLanguage.ENGLISH);
        enToLt = translator(TranslateLanguage.ENGLISH, TranslateLanguage.LITHUANIAN);
        probe = new AudioProbe(this);
        if (saved != null) { listening = saved.getBoolean("listening", true); font = saved.getFloat("font", 38); flipped = saved.getBoolean("flipped"); }
        resultEnglish = saved != null && saved.getBoolean("resultEnglish");
        hasResult = saved != null && saved.getBoolean("hasResult");
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(24)); root.setBackgroundColor(Color.rgb(244, 247, 242));
        scroll.addView(root); setContentView(scroll);
        // Respect status/navigation bars on Android 15+ edge-to-edge.
        scroll.setOnApplyWindowInsetsListener((v, insets) -> { android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()); v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets; });
        label(root, "LT ↔ EN  /  CONVERSATION", 14).setTypeface(null, Typeface.BOLD);
        label(root, "Understand. Then reply.", 28).setTypeface(null, Typeface.BOLD);
        button(root, "Debug timeline / export report", () -> startActivity(new Intent(this, DebugActivity.class)));
        status = label(root, "Checking offline language pack…", 16);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        playback = new EnglishPlayback(this, message -> { status.setText(message); refreshWatch(); });
        watch = new WatchBridge(this, this::watchCommand);
        workspace = new LinearLayout(this); workspace.setOrientation(LinearLayout.VERTICAL); root.addView(workspace);
        conversation = new LinearLayout(this); conversation.setOrientation(LinearLayout.VERTICAL); conversation.setPadding(0, 0, dp(12), dp(12)); workspace.addView(conversation);
        label(conversation, "1  CHOOSE WHO IS SPEAKING", 13).setTypeface(null, Typeface.BOLD);
        listenButton = button(conversation, "Listen  ·  Lithuanian → English", () -> selectDirection(true));
        replyButton = button(conversation, "Reply  ·  English → Lithuanian", () -> selectDirection(false));
        label(conversation, "Take short turns. The last translation stays visible while the next one is prepared.", 16);
        speakButton = button(conversation, "", this::openSpeech);
        speakButton.setTextSize(19); speakButton.setMinHeight(dp(64));
        sourcePreview = label(conversation, saved == null ? "" : saved.getString("sourcePreview", ""), 18);
        sourcePreview.setTextIsSelectable(true);
        sourcePreview.setVisibility(sourcePreview.length() == 0 ? View.GONE : View.VISIBLE);
        LinearLayout typed = disclosure(conversation, "Type or edit a phrase", false);
        input = new EditText(this); input.setMinLines(2); input.setTextSize(22); input.setGravity(Gravity.TOP); input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); typed.addView(input);
        button(typed, "Translate this phrase", this::translate);
        resultPanel = new LinearLayout(this); resultPanel.setOrientation(LinearLayout.VERTICAL); resultPanel.setPadding(dp(20), dp(16), dp(20), dp(16)); resultPanel.setBackground(surface(Color.WHITE)); workspace.addView(resultPanel);
        outputLabel = label(resultPanel, saved == null ? "2  TRANSLATION" : saved.getString("label", "Translation"), 14);
        outputLabel.setTypeface(null, Typeface.BOLD);
        output = label(resultPanel, saved == null ? "Ready when you are." : saved.getString("output", ""), font);
        output.setTextIsSelectable(true); output.setPadding(0, dp(20), 0, dp(20)); output.setRotation(flipped ? 180 : 0);
        LinearLayout controls = new LinearLayout(this); resultPanel.addView(controls);
        Button smaller = button(controls, "A−", () -> { font = Math.max(24, font - 6); output.setTextSize(font); }); smaller.setContentDescription("Decrease translation text size");
        Button larger = button(controls, "A+", () -> { font = Math.min(80, font + 6); output.setTextSize(font); }); larger.setContentDescription("Increase translation text size");
        button(controls, "Flip", () -> { flipped = !flipped; output.setRotation(flipped ? 180 : 0); }).setContentDescription("Rotate translation text 180 degrees");
        presentButton = button(resultPanel, "Show full screen", this::showTranslation);
        playbackButton = button(resultPanel, "Play English in glasses", () -> probe.stopThen(() -> playback.play(output.getText().toString())));
        playbackButton.setEnabled(hasResult && resultEnglish); presentButton.setEnabled(hasResult);
        button(resultPanel, "Stop playback", () -> {playback.stop();refreshWatch();});
        LinearLayout setup = disclosure(root, "Offline setup & glasses checks", false);
        label(setup, "Download both packs once on Wi-Fi: the translation pack here, then the speech model under Speak. After setup, recognition and translation run on your phone.", 16);
        button(setup, "Download translation language pack", this::download);
        button(setup, "Open speech setup & glasses connection", this::openSpeechSetup);
        watchToggle = new Switch(this); watchToggle.setText("Enable watch controls while phone is open"); setup.addView(watchToggle);
        watchToggle.setOnCheckedChangeListener((button, enabled) -> { watchArmed=enabled; refreshWatch(); });
        label(setup, "Watch turns use glasses unless you explicitly select the phone baseline on the speech screen. Playback needs a glasses output selected on this phone first.", 15);
        label(setup, "Bluetooth microphone check", 20);
        label(setup, "Pair HSTN in Bluetooth settings. This 15-second test reports the actual microphone and level; audio is discarded. Meta PCM is tested from the speech screen.", 16);
        diagnostic = label(setup, "Not recording", 16);
        button(setup, "Choose headset and test microphone", this::chooseHeadset);
        button(setup, "Stop microphone test", () -> { launchingSpeech=false;probe.stop(); diagnostic.setText("Stopped. Audio discarded."); });
        label(setup, "Experimental build • Validate glasses capture and translation quality before relying on a conversation.", 14);
        workspace.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            boolean wide = right - left >= dp(720);
            int orientation = wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL;
            if (workspace.getOrientation() != orientation || conversation.getLayoutParams().width == ViewGroup.LayoutParams.WRAP_CONTENT) {
                workspace.setOrientation(orientation);
                conversation.setLayoutParams(new LinearLayout.LayoutParams(wide ? 0 : -1, -2, wide ? 1f : 0f));
                resultPanel.setLayoutParams(new LinearLayout.LayoutParams(wide ? 0 : -1, -2, wide ? 1.25f : 0f));
            }
        });
        conversation.setLayoutParams(new LinearLayout.LayoutParams(-1, -2)); resultPanel.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        if (saved != null) input.setText(saved.getString("input", ""));
        refreshDirection(); checkModels();
    }
    private Translator translator(String source, String target) { return Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build()); }
    private void refreshDirection() {
        styleChoice(listenButton, listening); styleChoice(replyButton, !listening);
        speakButton.setText(listening ? "Listen to Lithuanian" : "Speak your English reply");
        input.setHint(listening ? "Type or paste Lithuanian" : "Type your English reply");
        // Keep the previous completed translation and its original language label visible.
    }
    private void selectDirection(boolean toEnglish) {
        if (listening == toEnglish) return;
        listening = toEnglish; gate.next(); translating=false;playback.stop(); input.setText(""); refreshDirection();refreshWatch();
        status.setText(ready ? "Ready • " + (listening ? "Listen to Lithuanian" : "Reply in English") : "Open offline setup to download the translation pack");
    }
    private void openSpeech() {
        if (!ready) { status.setText("Download the translation pack in Offline setup first"); return; }
        openSpeechSetup();
    }
    private void openSpeechSetup() {
        launchSpeech(false);
    }
    private void launchSpeech(boolean autoStart) {
        if(launchingSpeech)return;
        launchingSpeech=true;
        gate.next(); translating=false; playback.stop();
        probe.stopThen(() -> startActivityForResult(new Intent(this, SpeechActivity.class)
            .putExtra("language", listening ? "lt" : "en").putExtra("watchArmed",watchArmed).putExtra("autoStart",autoStart),101));
    }
    private String watchCommand(String action) {
        if(action.equals("play")) {
            if(!hasResult || !resultEnglish || !playback.canPlayOnSelectedDevice())return "Select glasses playback on the phone first.";
            probe.stopThen(() -> playback.playOnSelectedDevice(output.getText().toString())); return null;
        }
        if(action.equals("cancel")) { gate.next(); translating=false;launchingSpeech=false;playback.stop();probe.stop();refreshWatch();return null; }
        if(translating || launchingSpeech)return "Phone is still processing the previous command.";
        if(action.equals("listen_lt") || action.equals("reply_en")) {
            if(!ready)return "Download the translation pack on the phone first.";
            selectDirection(action.equals("listen_lt"));launchSpeech(true);return null;
        }
        return "No speech turn is active on this screen.";
    }
    private void refreshWatch() {
        if(watch!=null)watch.state(watchArmed&&ready,false,translating||launchingSpeech||playback.isBusy(),watchArmed&&!translating&&!launchingSpeech&&hasResult&&resultEnglish&&playback.canPlayOnSelectedDevice(),
            watchArmed?status.getText().toString():"Enable watch controls on the phone under Offline setup.");
    }
    private GradientDrawable surface(int color) { GradientDrawable background = new GradientDrawable(); background.setColor(color); background.setCornerRadius(dp(18)); return background; }
    private void styleChoice(Button view, boolean selected) {
        view.setSelected(selected); view.setTextColor(selected ? Color.WHITE : Color.rgb(17, 44, 43));
        view.setBackgroundTintList(android.content.res.ColorStateList.valueOf(selected ? Color.rgb(17, 78, 68) : Color.rgb(222, 233, 226)));
        view.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        view.setContentDescription(view.getText() + (selected ? ", selected" : ""));
    }
    private LinearLayout disclosure(LinearLayout parent, String title, boolean expanded) {
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        Button toggle = button(parent, title + (expanded ? "  −" : "  +"), () -> {});
        content.setVisibility(expanded ? View.VISIBLE : View.GONE); parent.addView(content);
        toggle.setOnClickListener(v -> { boolean open = content.getVisibility() != View.VISIBLE; content.setVisibility(open ? View.VISIBLE : View.GONE); toggle.setText(title + (open ? "  −" : "  +")); });
        return content;
    }
    private void showTranslation() {
        if (!hasResult) return;
        presentation = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(24), dp(24), dp(24), dp(24)); page.setBackgroundColor(Color.WHITE);
        page.setOnApplyWindowInsetsListener((v, insets) -> { android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()); v.setPadding(dp(24) + bars.left, dp(24) + bars.top, dp(24) + bars.right, dp(24) + bars.bottom); return insets; });
        label(page, resultEnglish ? "ENGLISH" : "LIETUVIŲ", 16);
        ScrollView reading = new ScrollView(this); page.addView(reading, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView phrase = new TextView(this); phrase.setText(output.getText()); phrase.setTextSize(font); phrase.setTextColor(Color.rgb(17,44,43)); phrase.setTextIsSelectable(true); phrase.setPadding(0, dp(24), 0, dp(24)); reading.addView(phrase);
        // Rotate the reading viewport, preserving scroll access for long phrases.
        reading.setRotation(flipped ? 180 : 0);
        button(page, "Flip for the person opposite", () -> { flipped = !flipped; reading.setRotation(flipped ? 180 : 0); output.setRotation(flipped ? 180 : 0); });
        button(page, "Back to conversation", () -> presentation.dismiss());
        presentation.setContentView(page); presentation.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); presentation.show(); presentation.getWindow().setLayout(-1, -1);
    }
    private void checkModels() {
        DebugLog.event("translation.models.check", "start");
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel.class).addOnSuccessListener(this, models -> {
            ready = models.stream().anyMatch(m -> m.getLanguage().equals(TranslateLanguage.LITHUANIAN));
            DebugLog.event("translation.models.ready", "lithuanian=" + ready + " installed=" + models.size());
            refreshWatch();
            status.setText(ready ? "Offline pack ready • translation stays on this phone" : "Download the Lithuanian pack before translating");
        }).addOnFailureListener(this, e -> { DebugLog.error("translation.models.check", e); status.setText("Cannot check models: " + e.getMessage()); });
    }
    private void download() {
        DebugLog.event("translation.models.download", "start wifiRequired=true");
        status.setText("Downloading language pack over Wi-Fi…");
        DownloadConditions conditions = new DownloadConditions.Builder().requireWifi().build();
        Tasks.whenAll(ltToEn.downloadModelIfNeeded(conditions), enToLt.downloadModelIfNeeded(conditions))
            .addOnSuccessListener(this, unused -> { DebugLog.event("translation.models.download", "complete"); checkModels(); })
            .addOnFailureListener(this, e -> { DebugLog.error("translation.models.download", e); status.setText("Download failed: " + e.getMessage()); });
    }
    private void translate() {
        if (!speechResult) DebugLog.beginTurn(listening ? "lt-en" : "en-lt", "typed");
        speechResult = false;
        long started = android.os.SystemClock.elapsedRealtime();
        playback.stop();
        String text = input.getText().toString().trim();
        if (text.isEmpty()) { DebugLog.event("translation.blocked", "empty input"); input.setError("Enter a phrase"); return; }
        sourcePreview.setText((listening ? "Latest Lithuanian input" : "Latest English input") + "\n" + text);
        sourcePreview.setVisibility(View.VISIBLE);
        if (!ready) { DebugLog.event("translation.blocked", "missing language pack"); status.setText("Download the offline pack first"); return; }
        long ticket = gate.next(); boolean toEnglish = listening;
        DebugLog.event("translation.start", "direction=" + (toEnglish ? "lt-en" : "en-lt") + " inputChars=" + text.length() + " ticket=" + ticket);
        status.setText("Translating locally…");
        translating=true;refreshWatch();
        (toEnglish ? ltToEn : enToLt).translate(text).addOnSuccessListener(this, result -> {
            if (!gate.accepts(ticket)) { DebugLog.event("translation.stale", "ticket=" + ticket); return; }
            DebugLog.event("translation.complete", "outputChars=" + result.length() + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - started));
            output.setText(result); outputLabel.setText(toEnglish ? "English • completed translation" : "Lietuvių • completed translation");
            hasResult = true; resultEnglish = toEnglish; playbackButton.setEnabled(toEnglish); presentButton.setEnabled(true);
            status.setText("Translated on this phone");
            translating=false;refreshWatch();
            PutDataMapRequest request = PutDataMapRequest.create("/translation/latest");
            request.getDataMap().putString("text", result);
            request.getDataMap().putString("language", toEnglish ? "English" : "Lietuvių");
            request.getDataMap().putLong("time", System.currentTimeMillis());
            Wearable.getDataClient(this).putDataItem(request.asPutDataRequest().setUrgent())
                .addOnSuccessListener(this, item -> DebugLog.event("watch.translation.synced", "success"))
                .addOnFailureListener(this, e -> { DebugLog.error("watch.translation.sync", e); status.setText("Translated locally • watch sync unavailable"); });
        }).addOnFailureListener(this, e -> { DebugLog.error("translation", e); if (gate.accepts(ticket)) { translating=false;refreshWatch();status.setText("Translation failed: " + e.getMessage()); } });
    }
    private void chooseHeadset() {
        playback.stop();
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
    private Button button(LinearLayout root, String title, Runnable action) { Button view = new Button(this); view.setText(title); view.setAllCaps(false); view.setMinHeight(dp(52)); view.setOnClickListener(v -> action.run()); root.addView(view, new LinearLayout.LayoutParams(root.getOrientation() == LinearLayout.HORIZONTAL ? 0 : -1, -2, root.getOrientation() == LinearLayout.HORIZONTAL ? 1 : 0)); return view; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        DebugLog.event("phone.activity.result", "request=" + request + " result=" + result + " hasData=" + (data != null));
        if (request == 101 && result == RESULT_OK && data != null) {
            if(data.hasExtra("language"))selectDirection(!"en".equals(data.getStringExtra("language")));
            watchArmed=data.getBooleanExtra("watchArmed",watchArmed);watchToggle.setChecked(watchArmed);
            String transcript = data.getStringExtra("transcript");
            if (transcript != null && !transcript.trim().isEmpty()) { DebugLog.event("phone.transcript.received", "chars=" + transcript.length()); speechResult=true; input.setText(transcript); translate(); }
        }
    }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); state.putBoolean("listening", listening); state.putFloat("font", font); state.putBoolean("flipped", flipped); state.putBoolean("resultEnglish", resultEnglish); state.putBoolean("hasResult", hasResult); state.putString("input", input.getText().toString()); state.putString("output", output.getText().toString()); state.putString("label", outputLabel.getText().toString()); state.putString("sourcePreview", sourcePreview.getText().toString()); }
    @Override protected void onResume() { super.onResume(); launchingSpeech=false;if(watch!=null){refreshWatch();watch.start();} }
    @Override protected void onPause() { if(watch!=null)watch.stop();if(playback!=null)playback.stop();if(probe!=null)probe.stop();launchingSpeech=false;super.onPause(); }
    @Override protected void onStop() { super.onStop(); if(translating){gate.next();translating=false;status.setText("Translation interrupted. Last completed result kept.");} probe.stop(); playback.stop(); diagnostic.setText("Not recording"); }
    @Override protected void onDestroy() { gate.next(); if (presentation != null) presentation.dismiss(); playback.close(); probe.close(); ltToEn.close(); enToLt.close(); super.onDestroy(); }
}
