package com.gnatok.translator;

import android.content.Context;
import android.os.*;
import com.google.android.gms.wearable.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Foreground-only command endpoint. No service can start a microphone in the background. */
final class WatchBridge implements MessageClient.OnMessageReceivedListener {
    interface Commands { String execute(String action); }
    private final Context context;private final Commands commands;
    private final Handler main=new Handler(Looper.getMainLooper());
    private static final LinkedHashMap<String,String> replies=new LinkedHashMap<>();
    private boolean active,requested,ready,recording,busy,canPlay;
    private String message="Open the speech screen on your phone.";
    WatchBridge(Context context,Commands commands){this.context=context;this.commands=commands;}
    private final Runnable heartbeat=new Runnable(){@Override public void run(){if(active){publish();main.postDelayed(this,5000);}}};
    void start(){
        DebugLog.event("watch.listener.start","alreadyRequested="+requested);
        if(requested)return;requested=true;
        Wearable.getMessageClient(context).addListener(this).addOnSuccessListener(unused->{
            if(!requested){Wearable.getMessageClient(context).removeListener(this);return;}
            active=true;main.removeCallbacks(heartbeat);main.post(heartbeat);
            DebugLog.event("watch.listener.ready","active=true");
        }).addOnFailureListener(error->{DebugLog.error("watch.listener.failed",error);active=false;requested=false;message="Watch controls unavailable; reopen the phone app.";publish();});
    }
    void stop(){DebugLog.event("watch.listener.stop","active="+active);requested=false;active=false;main.removeCallbacks(heartbeat);Wearable.getMessageClient(context).removeListener(this);ready=false;canPlay=false;message="Phone app is not active. Open it to continue.";publish();}
    void state(boolean ready,boolean recording,boolean busy,boolean canPlay,String message){this.ready=ready;this.recording=recording;this.busy=busy;this.canPlay=canPlay;this.message=message;if(active)publish();}
    private void publish(){
        PutDataMapRequest item=PutDataMapRequest.create("/translation/status");DataMap map=item.getDataMap();
        map.putLong("time",System.currentTimeMillis());map.putBoolean("ready",active&&ready);map.putBoolean("recording",active&&recording);
        map.putBoolean("busy",active&&busy);map.putBoolean("canPlay",active&&canPlay);map.putString("message",message);
        Wearable.getDataClient(context).putDataItem(item.asPutDataRequest().setUrgent()).addOnFailureListener(error->DebugLog.error("watch.status.failed",error));
    }
    @Override public void onMessageReceived(MessageEvent event){
        if(!"/translation/control".equals(event.getPath()))return;
        if(event.getData().length>2048){DebugLog.event("watch.command.rejected","payloadTooLarge bytes="+event.getData().length);return;}
        String node=event.getSourceNodeId();byte[] data=event.getData();
        main.post(()->{
            if(!active){DebugLog.event("watch.command.ignored","listenerInactive");return;} // A stopped Activity must not race its successor's acknowledgment.
            String id="";
            try{
                JSONObject command=new JSONObject(new String(data,StandardCharsets.UTF_8));id=command.getString("id");
                if(id.length()>100){DebugLog.event("watch.command.rejected","idTooLong");return;}
                String key=node+":"+id;
                if(replies.containsKey(key)){DebugLog.event("watch.command.duplicate","cachedAck=true");send(node,replies.get(key));return;}
                long age=System.currentTimeMillis()-command.getLong("sentAt");
                String action=command.getString("action");String error;
                DebugLog.event("watch.command.received","action="+safeAction(action)+" ageMs="+age+" ready="+ready+" recording="+recording+" busy="+busy+" canPlay="+canPlay);
                if(age < -5000 || age>15000)error="Command expired; try again.";
                else if(!active)error="Open the phone app first.";
                else if(action.equals("play")?!canPlay:!ready)error="Open the speech screen and enable watch controls on your phone.";
                else error=commands.execute(action);
                DebugLog.event("watch.command.result","action="+safeAction(action)+" accepted="+(error==null)+" expired="+(age < -5000 || age>15000));
                JSONObject ack=new JSONObject().put("id",id).put("ok",error==null).put("message",error==null?"Command accepted":error);
                String response=ack.toString();replies.put(key,response);if(replies.size()>100)replies.remove(replies.keySet().iterator().next());send(node,response);publish();
            }catch(Exception e){DebugLog.error("watch.command.failed",e);try{send(node,new JSONObject().put("id",id).put("ok",false).put("message","Invalid watch command").toString());}catch(JSONException ignored){}}
        });
    }
    private static String safeAction(String action){return Arrays.asList("listen_lt","reply_en","finish","cancel","play","stop").contains(action)?action:"unknown";}
    private void send(String node,String json){Wearable.getMessageClient(context).sendMessage(node,"/translation/control/ack",json.getBytes(StandardCharsets.UTF_8)).addOnSuccessListener(unused->DebugLog.event("watch.ack.sent","deliveredToTransport=true")).addOnFailureListener(error->DebugLog.error("watch.ack.failed",error));}
}
