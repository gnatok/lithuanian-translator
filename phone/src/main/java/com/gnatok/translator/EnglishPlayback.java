package com.gnatok.translator;

import android.Manifest;
import android.app.*;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.*;
import android.speech.tts.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Offline synthesis, then explicit Bluetooth AudioTrack routing. Never deliberately plays on the phone. */
final class EnglishPlayback implements AutoCloseable {
    private final Activity activity;
    private final Consumer<String> status;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private final TextToSpeech tts;
    private volatile boolean ready, closed;
    private volatile AudioTrack playing;
    private volatile Pending pending;
    private AudioDeviceInfo selected;
    private static final AudioAttributes ATTRIBUTES = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    private static final class Pending {
        final int ticket; final File file; final AudioDeviceInfo device;
        final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        int rate, channels, encoding;
        Pending(int ticket, File file, AudioDeviceInfo device) { this.ticket=ticket;this.file=file;this.device=device; }
    }
    EnglishPlayback(Activity activity, Consumer<String> status) {
        this.activity=activity; this.status=status; audio=activity.getSystemService(AudioManager.class);
        tts=new TextToSpeech(activity.getApplicationContext(), result -> main.post(() -> ready=result==TextToSpeech.SUCCESS));
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            private Pending current(String id) { Pending p=pending; return p!=null && id.equals(Integer.toString(p.ticket)) && p.ticket==generation.get()?p:null; }
            @Override public void onStart(String id) {}
            @Override public void onBeginSynthesis(String id,int rate,int encoding,int channels) {
                Pending p=current(id); if(p!=null) synchronized(p) { p.rate=rate;p.encoding=encoding;p.channels=channels; }
            }
            @Override public void onAudioAvailable(String id, byte[] bytes) {
                Pending p=current(id); if(p==null)return;
                synchronized(p) {
                    if(p.pcm.size()+bytes.length>8_000_000) { main.post(() -> fail(p.ticket,"Speech output is too long; play a shorter translation.")); return; }
                    p.pcm.write(bytes,0,bytes.length);
                }
            }
            @Override public void onDone(String id) {
                Pending p=current(id); if(p==null)return;
                try { worker.execute(() -> playPcm(p)); } catch(RejectedExecutionException ignored) { p.file.delete(); }
            }
            @Override public void onError(String id) { Pending p=current(id); if(p!=null)main.post(() -> fail(p.ticket,"Offline speech synthesis failed. Check your installed English voice.")); }
        });
    }
    void play(String text) {
        if(closed)return;
        if(activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},5010);
            status.accept("Allow nearby devices, then tap Play again.");return;
        }
        List<AudioDeviceInfo> devices=bluetoothOutputs();
        if(devices.isEmpty()) { status.accept("Connect glasses for media audio in Android Bluetooth settings.");return; }
        String[] labels=devices.stream().map(d->d.getProductName().toString()).toArray(String[]::new);
        new AlertDialog.Builder(activity).setTitle("Play English through…").setItems(labels,(dialog,which)->{
            selected=devices.get(which); synthesize(text,selected);
        }).show();
    }
    boolean canPlayOnSelectedDevice() { return ready && selected!=null && bluetoothOutputs().stream().anyMatch(d->d.getId()==selected.getId()); }
    boolean playOnSelectedDevice(String text) {
        if(!canPlayOnSelectedDevice())return false;
        synthesize(text,selected);return true;
    }
    private List<AudioDeviceInfo> bluetoothOutputs() {
        List<AudioDeviceInfo> result=new ArrayList<>();
        for(AudioDeviceInfo d:audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
            if(d.getType()==AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || d.getType()==AudioDeviceInfo.TYPE_BLE_HEADSET)result.add(d);
        return result;
    }
    private void synthesize(String text,AudioDeviceInfo device) {
        stop(); int ticket=generation.get();
        if(!ready) { status.accept("Speech engine is not ready. Try again shortly.");return; }
        Voice voice=tts.getVoices()==null?null:tts.getVoices().stream().filter(v->v.getLocale().getLanguage().equals("en") && !v.isNetworkConnectionRequired()
            && !v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)).findFirst().orElse(null);
        if(voice==null || tts.setVoice(voice)!=TextToSpeech.SUCCESS) { status.accept("Install an offline English voice in Android text-to-speech settings.");return; }
        if(text.trim().isEmpty() || text.length()>TextToSpeech.getMaxSpeechInputLength()) { status.accept("Choose a shorter completed English translation.");return; }
        try {
            File file=File.createTempFile("english-", ".wav",activity.getCacheDir());
            Pending p=new Pending(ticket,file,device); pending=p;
            status.accept("Preparing English speech offline…");
            if(tts.synthesizeToFile(text,new Bundle(),file,Integer.toString(ticket))!=TextToSpeech.SUCCESS)fail(ticket,"Could not prepare offline speech.");
        } catch(IOException e) { fail(ticket,"Cannot create temporary speech audio."); }
    }
    private void playPcm(Pending p) {
        AudioTrack track=null;
        AudioFocusRequest focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(ATTRIBUTES)
            .setOnAudioFocusChangeListener(change->{ if(change<0)main.post(()->fail(p.ticket,"Playback stopped for an audio interruption.")); },main).build();
        try {
            byte[] bytes; synchronized(p) { bytes=p.pcm.toByteArray(); }
            if(p.ticket!=generation.get())return;
            if(p.encoding!=AudioFormat.ENCODING_PCM_16BIT || p.rate<8000 || p.rate>48000 || (p.channels!=1 && p.channels!=2) || bytes.length==0)
                throw new IOException("This voice does not provide supported offline PCM audio.");
            int mask=p.channels==1?AudioFormat.CHANNEL_OUT_MONO:AudioFormat.CHANNEL_OUT_STEREO;
            int frameBytes=2*p.channels;
            int chunk=Math.max(frameBytes,p.rate*frameBytes/50);
            int minimum=AudioTrack.getMinBufferSize(p.rate,mask,p.encoding);
            if(minimum<=0)throw new IOException("Unsupported speech audio format");
            if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)throw new IOException("Audio focus unavailable");
            track=new AudioTrack.Builder().setAudioAttributes(ATTRIBUTES).setAudioFormat(new AudioFormat.Builder().setSampleRate(p.rate).setChannelMask(mask).setEncoding(p.encoding).build())
                .setBufferSizeInBytes(Math.max(minimum,chunk*2)).setTransferMode(AudioTrack.MODE_STREAM).build();
            if(!track.setPreferredDevice(p.device))throw new IOException("Glasses output route rejected");
            final AudioTrack monitored=track;
            AtomicBoolean audible=new AtomicBoolean(false);
            track.addOnRoutingChangedListener(router->{
                AudioDeviceInfo actual=monitored.getRoutedDevice();
                if(audible.get() && (actual==null || actual.getId()!=p.device.getId())) { try { monitored.pause();monitored.flush(); }catch(IllegalStateException ignored){} main.post(()->fail(p.ticket,"Glasses audio route changed. Playback stopped.")); }
            },main);
            if(p.ticket!=generation.get())return;
            playing=track;track.play();
            byte[] silence=new byte[chunk];long began=SystemClock.elapsedRealtime();
            long primedFrames=0;
            while(p.ticket==generation.get() && (track.getRoutedDevice()==null || track.getRoutedDevice().getId()!=p.device.getId())) {
                if(SystemClock.elapsedRealtime()-began>2000)throw new IOException("Could not verify glasses media route");
                int primed=track.write(silence,0,silence.length,AudioTrack.WRITE_BLOCKING);
                if(primed<=0)throw new IOException("Audio route setup failed");primedFrames+=primed/frameBytes;
            }
            notify(p.ticket,"Playing English in "+p.device.getProductName());
            audible.set(true);
            long writtenFrames=primedFrames;
            for(int offset=0;offset<bytes.length && p.ticket==generation.get();) {
                if(track.getRoutedDevice()==null || track.getRoutedDevice().getId()!=p.device.getId())throw new IOException("Glasses disconnected; playback stopped");
                int n=track.write(bytes,offset,Math.min(chunk,bytes.length-offset),AudioTrack.WRITE_BLOCKING);
                if(n<=0)throw new IOException("Speech audio output interrupted");
                offset+=n;writtenFrames+=n/frameBytes;
            }
            long deadline=SystemClock.elapsedRealtime()+1000;
            while(p.ticket==generation.get() && Integer.toUnsignedLong(track.getPlaybackHeadPosition())<writtenFrames && SystemClock.elapsedRealtime()<deadline)Thread.sleep(20);
            notify(p.ticket,"English playback finished");
        } catch(Exception e) { notify(p.ticket,e.getMessage()==null?"Playback failed":e.getMessage()); }
        finally {
            if(track!=null){ if(playing==track)playing=null;try{track.pause();track.flush();}catch(IllegalStateException ignored){}track.release(); }
            audio.abandonAudioFocusRequest(focus);p.file.delete();if(pending==p)pending=null;
        }
    }
    private void notify(int ticket,String message) { main.post(()->{if(!closed && ticket==generation.get())status.accept(message);}); }
    private void fail(int ticket,String message) { if(ticket==generation.get()){stop();status.accept(message);} }
    void stop() {
        generation.incrementAndGet();tts.stop();
        AudioTrack current=playing;if(current!=null)try{current.pause();current.flush();}catch(IllegalStateException ignored){}
        Pending p=pending;pending=null;if(p!=null)p.file.delete();
    }
    @Override public void close(){closed=true;stop();tts.shutdown();worker.shutdown();}
}
