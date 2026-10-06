package com.gnatok.translator;

import android.content.Context;
import android.annotation.SuppressLint;
import android.media.*;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Short, foreground-only HFP diagnostic. Audio is measured and discarded. */
final class AudioProbe implements AutoCloseable {
    private final AudioManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean running;
    private volatile boolean busy;
    private volatile boolean closed;
    private volatile int generation;
    AudioProbe(Context context) { manager = context.getSystemService(AudioManager.class); }
    @SuppressLint("MissingPermission") // MainActivity checks BLUETOOTH_CONNECT before calling.
    List<AudioDeviceInfo> devices() {
        return manager.getAvailableCommunicationDevices().stream()
            .filter(d -> d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || d.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET).collect(java.util.stream.Collectors.toList());
    }
    @SuppressLint("MissingPermission") // MainActivity gates both RECORD_AUDIO and BLUETOOTH_CONNECT; revocation is caught below.
    void start(AudioDeviceInfo selected, Consumer<String> report) {
        if (closed) return;
        if (busy) { report.accept("Previous microphone session is active or stopping. Try again shortly."); return; }
        busy = true;
        DebugLog.event("probe.start","selectedType="+selected.getType()+" selectedId="+selected.getId()+" rate=16000 channels=1 encoding=PCM16");
        running = true;
        final int ticket = ++generation;
        worker.execute(() -> {
            AudioRecord recorder = null;
            int previousMode = AudioManager.MODE_NORMAL;
            AudioDeviceInfo previousDevice = null;
            boolean restoreRoute = false;
            try {
                previousMode = manager.getMode();
                previousDevice = manager.getCommunicationDevice();
                restoreRoute = true;
                if (!running || ticket != generation) return;
                manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                if (!manager.setCommunicationDevice(selected)) throw new IllegalStateException("Android rejected the selected headset route");
                int minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (minimum <= 0) throw new IllegalStateException("16 kHz recording unavailable");
                recorder = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(minimum, 6400)).build();
                if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Recorder did not initialize");
                recorder.startRecording();
                long started = SystemClock.elapsedRealtime(), last = 0, lastLogged = 0;
                long totalSamples = 0;
                int lastRouteId = Integer.MIN_VALUE;
                short[] samples = new short[1600];
                while (running && SystemClock.elapsedRealtime() - started < 15000) {
                    int count = recorder.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);
                    if (count <= 0) throw new IllegalStateException("Audio read failed: " + count);
                    totalSamples += count;
                    AudioDeviceInfo actual = recorder.getRoutedDevice();
                    int routeId=actual==null?-1:actual.getId();
                    if(routeId!=lastRouteId){lastRouteId=routeId;DebugLog.event("probe.route","actualId="+routeId+" actualType="+(actual==null?-1:actual.getType())+" elapsedMs="+(SystemClock.elapsedRealtime()-started));}
                    // Output and input device IDs differ; inspect actual input transport and label.
                    boolean bluetooth = actual != null && (actual.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || actual.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET);
                    if (!bluetooth && SystemClock.elapsedRealtime() - started > 2000) throw new IllegalStateException("Bluetooth input was not established. Stopped; phone microphone is not accepted.");
                    if (SystemClock.elapsedRealtime() - last > 300) {
                        last = SystemClock.elapsedRealtime();
                        double energy = 0;
                        for (int i = 0; i < count; i++) energy += (double)samples[i] * samples[i];
                        double db = 20 * Math.log10(Math.max(1, Math.sqrt(energy / count)) / 32768.0);
                        if(last-lastLogged>=1000){lastLogged=last;DebugLog.event("probe.level",String.format(java.util.Locale.US,"dbfs=%.1f samples=%d elapsedMs=%d bluetooth=%s",db,totalSamples,last-started,bluetooth));}
                        String value = "Selected: " + selected.getProductName() + "\nActual input: " + (actual == null ? "waiting" : actual.getProductName() + " (type " + actual.getType() + ")")
                            + "\n" + (bluetooth ? "Bluetooth input confirmed" : "Waiting for route")
                            + String.format(java.util.Locale.US, "\nLevel: %.1f dBFS • 16 kHz mono\n%d / 15 seconds", db, (last - started) / 1000);
                        post(ticket, report, value);
                    }
                }
                post(ticket, report, "Diagnostic finished. Audio was discarded. Repeat with the other speaker talking at 1 metre; compare signal levels. This does not yet measure transcription quality.");
                DebugLog.event("probe.complete","samples="+totalSamples+" elapsedMs="+(SystemClock.elapsedRealtime()-started)+" cancelled="+!running);
            } catch (Exception error) { DebugLog.error("probe.failed",error);post(ticket, report, "Audio diagnostic: " + error.getMessage()); }
            finally {
                if (recorder != null) {
                    try { recorder.stop(); } catch (RuntimeException error) {DebugLog.error("probe.stop.failed",error);}
                    try { recorder.release(); } catch (RuntimeException error) {DebugLog.error("probe.release.failed",error);}
                }
                if (restoreRoute) {
                    try { manager.clearCommunicationDevice(); if (previousDevice != null) manager.setCommunicationDevice(previousDevice); } catch (RuntimeException error) {DebugLog.error("probe.route.restore.failed",error);}
                    try { manager.setMode(previousMode); } catch (RuntimeException error) {DebugLog.error("probe.mode.restore.failed",error);}
                }
                running = false;
                busy = false;
                DebugLog.event("probe.released","routeRestorationAttempted="+restoreRoute);
            }
        });
    }
    private void post(int ticket, Consumer<String> report, String value) { main.post(() -> { if (ticket == generation) report.accept(value); }); }
    void stop() { if(running)DebugLog.event("probe.stop","requested=true");running = false; generation++; }
    /** Call on the main thread. A later stop/start/close invalidates this pending transition. */
    void stopThen(Runnable continuation) {
        if (closed) return;
        stop();
        final int ticket = generation;
        // The executor barrier runs only after recorder release and route restoration complete.
        worker.execute(() -> main.post(() -> {
            if (!closed && ticket == generation && !busy) continuation.run();
        }));
    }
    @Override public void close() { closed = true; stop(); worker.shutdown(); }
}
