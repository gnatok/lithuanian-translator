package com.gnatok.translator;

import android.content.Context;
import android.media.*;
import android.net.*;
import android.os.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Local bounded JSON-lines flight recorder. No audio buffers or conversation text are retained. */
public final class DebugLog {
    private static final Object LOCK=new Object();
    private static final ExecutorService writer=Executors.newSingleThreadExecutor();
    private static final AtomicLong sequence=new AtomicLong();
    private static final String session=UUID.randomUUID().toString().substring(0,8);
    private static final long started=SystemClock.elapsedRealtime();
    private static volatile File directory;
    private static volatile String turn="none",lastStage="startup",lastError="none";
    private static final Map<String,Long> audioTimes=new HashMap<>();
    private static final Map<String,Long> audioSamples=new HashMap<>();
    private DebugLog() {}
    public static void initialize(Context context) {
        directory=new File(context.getApplicationContext().getNoBackupFilesDir(),"diagnostics");directory.mkdirs();
        event("app.start","version="+BuildConfig.VERSION_NAME+" code="+BuildConfig.VERSION_CODE+" commit="+BuildConfig.GIT_SHA+" device="+Build.MANUFACTURER+" "+Build.MODEL+" android="+Build.VERSION.RELEASE+" sdk="+Build.VERSION.SDK_INT+" abi="+Arrays.toString(Build.SUPPORTED_ABIS));
        Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
            write(line("app.crash","thread="+thread.getName()+" "+trace(error)));
            if(previous!=null)previous.uncaughtException(thread,error);
            else {android.os.Process.killProcess(android.os.Process.myPid());System.exit(10);}
        });
    }
    public static String beginTurn(String direction,String source) {
        turn=UUID.randomUUID().toString().substring(0,8);
        synchronized(audioTimes){audioTimes.clear();audioSamples.clear();}
        event("turn.begin","direction="+direction+" source="+source);return turn;
    }
    public static void event(String stage,String detail) {
        lastStage=stage;
        if (stage.endsWith(".error") || stage.endsWith(".failure") || stage.endsWith(".failed"))
            lastError=stage+": "+limit(detail,2000);
        String entry=line(stage,detail);
        writer.execute(()->write(entry));
    }
    public static void error(String stage,Throwable error) {
        lastError=stage+": "+error.getClass().getSimpleName()+": "+String.valueOf(error.getMessage());
        event(stage+".error",trace(error));
    }
    private static String trace(Throwable error) {
        StringWriter text=new StringWriter();error.printStackTrace(new PrintWriter(text));
        return limit(text.toString(),12000);
    }
    private static String limit(String value,int maximum){return value==null?"":value.length()>maximum?value.substring(0,maximum)+"…":value;}
    private static String line(String stage,String detail) {
        try{return new JSONObject().put("time",new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX",Locale.US).format(new Date()))
            .put("elapsedMs",SystemClock.elapsedRealtime()-started).put("seq",sequence.incrementAndGet()).put("session",session).put("turn",turn)
            .put("stage",stage).put("detail",limit(detail,12000)).toString();}
        catch(Exception e){return "{\"stage\":\"diagnostics.encoding_error\"}";}
    }
    private static void write(String entry) {
        synchronized(LOCK){
            if(directory==null)return;
            try{
                File current=new File(directory,"current.jsonl");
                if(current.length()>1024*1024){
                    new File(directory,"older.jsonl").delete();
                    new File(directory,"previous.jsonl").renameTo(new File(directory,"older.jsonl"));
                    current.renameTo(new File(directory,"previous.jsonl"));
                }
                try(FileOutputStream output=new FileOutputStream(current,true)){output.write((entry+"\n").getBytes(StandardCharsets.UTF_8));}
            }catch(IOException ignored){android.util.Log.e("TranslatorDebug","Could not write local diagnostics");}
        }
    }
    /** The source calls this each frame; computation and logging happen only once per second. */
    public static void audio(String source,ByteBuffer pcm,long timestampUs) {
        long now=SystemClock.elapsedRealtime(),total;
        synchronized(audioTimes){
            total=audioSamples.getOrDefault(source,0L)+pcm.remaining()/2;audioSamples.put(source,total);
            Long previous=audioTimes.get(source);if(previous!=null&&now-previous<1000)return;audioTimes.put(source,now);
        }
        ByteBuffer data=pcm.duplicate().order(ByteOrder.LITTLE_ENDIAN);double energy=0;int peak=0,count=0;
        while(data.remaining()>=2){int sample=data.getShort();energy+=(double)sample*sample;peak=Math.max(peak,Math.abs(sample));count++;}
        double db=count==0?-96:20*Math.log10(Math.max(1,Math.sqrt(energy/count))/32768d);
        event("audio.frames",String.format(Locale.US,"source=%s sampleRate=16000 channels=1 totalSamples=%d frameSamples=%d ptsUs=%d rmsDbFS=%.1f peak=%d",source,total,count,timestampUs,db,peak));
    }
    public static String summary(Context context) {
        StringBuilder text=new StringBuilder("LT ↔ EN diagnostic report\n");
        text.append("Version: ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\nCommit: ").append(BuildConfig.GIT_SHA)
            .append("\nDevice: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" / Android ").append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT)
            .append("\nSession: ").append(session).append(" / turn: ").append(turn).append("\nLast stage: ").append(lastStage).append("\nLast exception: ").append(lastError);
        for(String permission:new String[]{"android.permission.RECORD_AUDIO","android.permission.BLUETOOTH_CONNECT","android.permission.CAMERA"})
            text.append("\n").append(permission).append(" = ").append(context.checkSelfPermission(permission)==android.content.pm.PackageManager.PERMISSION_GRANTED?"granted":"denied");
        try{
            ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);NetworkCapabilities caps=manager.getNetworkCapabilities(manager.getActiveNetwork());
            text.append("\nNetwork: ").append(caps==null?"none":"wifi="+caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)+" cellular="+caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)+" validated="+caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            ActivityManagerInfo.append(context,text);
            AudioManager audio=context.getSystemService(AudioManager.class);
            text.append("\nAudio mode: ").append(audio.getMode());
            for(AudioDeviceInfo device:audio.getDevices(AudioManager.GET_DEVICES_ALL))text.append("\nAudio device: id=").append(device.getId()).append(" type=").append(device.getType()).append(" input=").append(device.isSource()).append(" output=").append(device.isSink());
        }catch(Exception e){text.append("\nSnapshot error: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());}
        text.append("\nLogs exclude recorded audio and transcript/translation text. Export only when you choose.\n");return text.toString();
    }
    private static class ActivityManagerInfo {
        static void append(Context context,StringBuilder text){
            android.app.ActivityManager.MemoryInfo info=new android.app.ActivityManager.MemoryInfo();context.getSystemService(android.app.ActivityManager.class).getMemoryInfo(info);
            text.append("\nAvailable RAM MB: ").append(info.availMem/(1024*1024)).append(" lowMemory=").append(info.lowMemory).append(" freeStorageMB=").append(context.getNoBackupFilesDir().getUsableSpace()/(1024*1024));
        }
    }
    public static String report(Context context) {
        // Called by the debug screen's worker; barrier includes previously queued events.
        try{writer.submit(()->{}).get(3,TimeUnit.SECONDS);}catch(Exception ignored){}
        StringBuilder text=new StringBuilder(summary(context));text.append("\nEVENT TIMELINE (oldest first)\n");
        synchronized(LOCK){if(directory!=null)for(String name:new String[]{"older.jsonl","previous.jsonl","current.jsonl"}){
            File file=new File(directory,name);if(!file.exists())continue;
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null)text.append(line).append('\n');}
            catch(IOException e){text.append("Read error: ").append(e.getMessage()).append('\n');}
        }}return text.toString();
    }
    public static void clear(){writer.execute(()->{synchronized(LOCK){if(directory!=null)for(String name:new String[]{"older.jsonl","previous.jsonl","current.jsonl"})new File(directory,name).delete();lastError="none";}write(line("diagnostics.cleared","User cleared local history"));});}
}
