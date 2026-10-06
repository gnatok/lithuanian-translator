package com.gnatok.translator.wear;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.*;
import com.google.android.gms.wearable.*;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.json.JSONObject;

/** Last completed result stays readable while transient, acknowledged controls run on the phone. */
public final class MainActivity extends Activity implements DataClient.OnDataChangedListener, MessageClient.OnMessageReceivedListener {
    private static final long FRESH_MS = 15_000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> nodes = new HashSet<>();
    private TextView connection, result, resultTime, feedback;
    private Button listen, reply, finish, cancel, play;
    private long latest, statusTime;
    private String phoneNode, pendingId, pendingNode;
    private boolean ready, recording, busy, canPlay, started, listenerReady;
    private String phoneMessage = "Open the speech screen on your phone and enable watch controls.";
    private final Runnable timeout = () -> {
        pendingId = null; pendingNode = null;
        feedback.setText("No phone confirmation. Check the phone before trying again."); render();
    };
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!started) return;
            Wearable.getNodeClient(MainActivity.this).getConnectedNodes().addOnSuccessListener(found -> {
                if (!started) return;
                nodes.clear(); for (Node node : found) nodes.add(node.getId()); render();
            }).addOnFailureListener(error -> { nodes.clear(); render(); });
            render(); handler.postDelayed(this, 5_000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Color.BLACK);
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL); column.setGravity(Gravity.CENTER_HORIZONTAL);
        int inset = dp(28); column.setPadding(inset, dp(34), inset, dp(40));
        text(column, "LT ↔ EN", 18, 0xff9ce6d4);
        connection = text(column, "Connecting to phone…", 12, 0xffc5cbd3);
        result = text(column, "Your latest translation appears here.", 23, Color.WHITE);
        result.setPadding(0, dp(12), 0, dp(4));
        resultTime = text(column, "No completed translation yet", 11, 0xffa7afbb);
        listen = button(column, "Listen · LT → EN", () -> send("listen_lt"));
        reply = button(column, "Reply · EN → LT", () -> send("reply_en"));
        finish = button(column, "Finish & translate", () -> send("finish"));
        cancel = button(column, "Cancel turn", () -> send("cancel"));
        play = button(column, "Play English", () -> send("play"));
        feedback = text(column, "Controls use the audio source selected on your phone.", 12, 0xffc5cbd3);
        scroll.addView(column); setContentView(scroll); render();
    }

    private TextView text(LinearLayout parent, String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setGravity(Gravity.CENTER);
        view.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    private Button button(LinearLayout parent, String label, Runnable action) {
        Button view = new Button(this); view.setText(label); view.setTextSize(14); view.setAllCaps(false); view.setMinHeight(dp(48));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(4);
        parent.addView(view, params); view.setOnClickListener(ignored -> action.run()); return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public void onStart() {
        super.onStart(); started = true;
        listenerReady = false;
        Wearable.getDataClient(this).addListener(this);
        Wearable.getMessageClient(this).addListener(this).addOnSuccessListener(ignored -> {
            listenerReady = started; render();
        }).addOnFailureListener(error -> { listenerReady = false; feedback.setText("Phone controls unavailable. Reopen the watch app."); render(); });
        Wearable.getDataClient(this).getDataItems().addOnSuccessListener(items -> {
            try { for (DataItem item : items) show(item); } finally { items.release(); }
        }).addOnFailureListener(error -> feedback.setText("Phone sync unavailable. Open the companion app on your phone."));
        handler.post(refresh);
    }
    @Override public void onStop() {
        started = false; listenerReady = false; handler.removeCallbacks(refresh); handler.removeCallbacks(timeout);
        if (pendingId != null) feedback.setText("Command confirmation interrupted. Check the phone.");
        pendingId = null; pendingNode = null;
        Wearable.getMessageClient(this).removeListener(this); Wearable.getDataClient(this).removeListener(this); super.onStop();
    }
    @Override public void onDataChanged(DataEventBuffer events) {
        for (DataEvent event : events) {
            if (event.getType() == DataEvent.TYPE_CHANGED) show(event.getDataItem());
            else if ("/translation/status".equals(event.getDataItem().getUri().getPath())) { statusTime = 0; render(); }
        }
    }
    private void show(DataItem item) {
        String path = item.getUri().getPath();
        if (!"/translation/latest".equals(path) && !"/translation/status".equals(path)) return;
        DataMap data = DataMapItem.fromDataItem(item).getDataMap();
        if ("/translation/status".equals(path)) {
            long time = data.getLong("time"); if (time < statusTime) return;
            statusTime = time; phoneNode = item.getUri().getHost();
            ready = data.getBoolean("ready"); recording = data.getBoolean("recording"); busy = data.getBoolean("busy"); canPlay = data.getBoolean("canPlay");
            phoneMessage = data.getString("message", "Open the speech screen on your phone and enable watch controls.");
            render(); return;
        }
        if (data.getLong("time") < latest) return;
        latest = data.getLong("time"); result.setText(data.getString("text", ""));
        resultTime.setText(data.getString("language", "") + " · Last result\n" + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(latest)));
    }
    private boolean fresh() {
        long age = System.currentTimeMillis() - statusTime;
        return phoneNode != null && nodes.contains(phoneNode) && age >= -5_000 && age <= FRESH_MS;
    }
    private void render() {
        boolean live = fresh(), idle = pendingId == null && listenerReady;
        connection.setText(nodes.isEmpty() ? "Phone disconnected · showing last result" : !live ? "Open the app on your phone · status expired" : phoneMessage);
        listen.setEnabled(live && idle && ready && !recording && !busy);
        reply.setEnabled(live && idle && ready && !recording && !busy);
        finish.setEnabled(live && idle && recording); cancel.setEnabled(live && idle && (recording || busy));
        play.setEnabled(live && idle && canPlay && !recording && !busy);
        finish.setVisibility(recording ? android.view.View.VISIBLE : android.view.View.GONE);
        cancel.setVisibility(recording || busy ? android.view.View.VISIBLE : android.view.View.GONE);
    }
    private void send(String action) {
        if (!fresh() || !listenerReady || pendingId != null) { render(); return; }
        try {
            String id = UUID.randomUUID().toString(); pendingId = id; pendingNode = phoneNode;
            JSONObject request = new JSONObject().put("id", id).put("action", action).put("sentAt", System.currentTimeMillis());
            feedback.setText("Waiting for phone confirmation…"); render(); handler.postDelayed(timeout, 8_000);
            Wearable.getMessageClient(this).sendMessage(pendingNode, "/translation/control", request.toString().getBytes(StandardCharsets.UTF_8))
                .addOnFailureListener(error -> {
                    if (!id.equals(pendingId)) return;
                    handler.removeCallbacks(timeout); pendingId = null; pendingNode = null;
                    feedback.setText("Command not sent. Reconnect and check the phone."); render();
                });
        } catch (org.json.JSONException error) {
            pendingId = null; pendingNode = null; feedback.setText("Could not prepare command."); render();
        }
    }
    @Override public void onMessageReceived(MessageEvent event) {
        if (!"/translation/control/ack".equals(event.getPath()) || pendingId == null || !event.getSourceNodeId().equals(pendingNode)) return;
        try {
            JSONObject ack = new JSONObject(new String(event.getData(), StandardCharsets.UTF_8));
            if (!pendingId.equals(ack.optString("id"))) return;
            handler.removeCallbacks(timeout); pendingId = null; pendingNode = null;
            feedback.setText((ack.optBoolean("ok") ? "" : "Phone: ") + ack.optString("message", "Check the phone.")); render();
        } catch (org.json.JSONException ignored) { /* Wait for a valid, correlated acknowledgement. */ }
    }
}
