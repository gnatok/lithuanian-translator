package com.gnatok.translator;

import android.content.Context;
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
    private volatile int generation;
    AudioProbe(Context context) { manager = context.getSystemService(AudioManager.class); }
    List<AudioDeviceInfo> devices() {
        return manager.getAvailableCommunicationDevices().stream()
            .filter(d -> d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || d.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET).collect(java.util.stream.Collectors.toList());
    }
    void start(AudioDeviceInfo selected, Consumer<String> report) {
        if (running) return;
        running = true;
        final int ticket = ++generation;
        worker.execute(() -> {
            AudioRecord recorder = null;
            int previousMode = manager.getMode();
            AudioDeviceInfo previousDevice = manager.getCommunicationDevice();
            try {
                manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                if (!manager.setCommunicationDevice(selected)) throw new IllegalStateException("Android rejected the selected headset route");
                int minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (minimum <= 0) throw new IllegalStateException("16 kHz recording unavailable");
                recorder = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(minimum, 6400)).build();
                if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Recorder did not initialize");
                recorder.startRecording();
                long started = SystemClock.elapsedRealtime(), last = 0;
                short[] samples = new short[1600];
                while (running && SystemClock.elapsedRealtime() - started < 15000) {
                    int count = recorder.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);
                    if (count <= 0) throw new IllegalStateException("Audio read failed: " + count);
                    AudioDeviceInfo actual = recorder.getRoutedDevice();
                    // Output and input device IDs differ; inspect actual input transport and label.
                    boolean bluetooth = actual != null && (actual.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || actual.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET);
                    if (!bluetooth && SystemClock.elapsedRealtime() - started > 2000) throw new IllegalStateException("Bluetooth input was not established. Stopped; phone microphone is not accepted.");
                    if (SystemClock.elapsedRealtime() - last > 300) {
                        last = SystemClock.elapsedRealtime();
                        double energy = 0;
                        for (int i = 0; i < count; i++) energy += (double)samples[i] * samples[i];
                        double db = 20 * Math.log10(Math.max(1, Math.sqrt(energy / count)) / 32768.0);
                        String value = "Selected: " + selected.getProductName() + "\nActual input: " + (actual == null ? "waiting" : actual.getProductName() + " (type " + actual.getType() + ")")
                            + "\n" + (bluetooth ? "Bluetooth input confirmed" : "Waiting for route")
                            + String.format(java.util.Locale.US, "\nLevel: %.1f dBFS • 16 kHz mono\n%d / 15 seconds", db, (last - started) / 1000);
                        post(ticket, report, value);
                    }
                }
                post(ticket, report, "Diagnostic finished. Audio was discarded. Repeat with the other speaker talking at 1 metre; compare signal levels. This does not yet measure transcription quality.");
            } catch (Exception error) { post(ticket, report, "Audio diagnostic: " + error.getMessage()); }
            finally {
                if (recorder != null) { try { recorder.stop(); } catch (IllegalStateException ignored) {} recorder.release(); }
                try { manager.clearCommunicationDevice(); if (previousDevice != null) manager.setCommunicationDevice(previousDevice); manager.setMode(previousMode); } catch (RuntimeException ignored) {}
                running = false;
            }
        });
    }
    private void post(int ticket, Consumer<String> report, String value) { main.post(() -> { if (ticket == generation) report.accept(value); }); }
    void stop() { running = false; generation++; }
    @Override public void close() { stop(); worker.shutdown(); }
}
