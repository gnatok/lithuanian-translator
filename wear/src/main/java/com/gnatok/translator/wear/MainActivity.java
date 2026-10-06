package com.gnatok.translator.wear;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;
import com.google.android.gms.wearable.*;
import java.text.DateFormat;
import java.util.Date;

/** Displays explicitly timestamped completed results, including cached offline results. */
public final class MainActivity extends Activity implements DataClient.OnDataChangedListener {
    private TextView result;
    private long latest;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL); column.setGravity(Gravity.CENTER);
        int inset = (int)(32 * getResources().getDisplayMetrics().density); column.setPadding(inset, inset, inset, inset);
        TextView title = new TextView(this); title.setText("LT ↔ EN"); title.setGravity(Gravity.CENTER); title.setTextSize(20); column.addView(title);
        result = new TextView(this); result.setTextSize(24); result.setGravity(Gravity.CENTER); result.setText("Translate a phrase on your phone. The last result appears here."); column.addView(result);
        scroll.addView(column); setContentView(scroll);
    }
    @Override public void onStart() {
        super.onStart(); Wearable.getDataClient(this).addListener(this);
        Wearable.getDataClient(this).getDataItems().addOnSuccessListener(items -> {
            try { for (DataItem item : items) show(item); } finally { items.release(); }
        }).addOnFailureListener(this, e -> result.setText("Phone sync unavailable. Open the companion app on your phone."));
    }
    @Override public void onStop() { Wearable.getDataClient(this).removeListener(this); super.onStop(); }
    @Override public void onDataChanged(DataEventBuffer events) { for (DataEvent event : events) if (event.getType() == DataEvent.TYPE_CHANGED) show(event.getDataItem()); }
    private void show(DataItem item) {
        if (!"/translation/latest".equals(item.getUri().getPath())) return;
        DataMap data = DataMapItem.fromDataItem(item).getDataMap();
        if (data.getLong("time") < latest) return;
        latest = data.getLong("time");
        String time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(data.getLong("time")));
        result.setText(data.getString("language") + "\n\n" + data.getString("text") + "\n\nLast result • " + time);
    }
}
