package com.gnatok.translator;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Separate inspect/export screen; opening it never uploads diagnostics. */
public final class DebugActivity extends Activity {
    private TextView timeline,message;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private String report="";
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);int pad=(int)(16*getResources().getDisplayMetrics().density);root.setPadding(pad,pad,pad,pad);
        root.setOnApplyWindowInsetsListener((view,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());view.setPadding(pad+bars.left,pad+bars.top,pad+bars.right,pad+bars.bottom);return insets;});
        setContentView(root);
        TextView title=new TextView(this);title.setText("Debug timeline");title.setTextSize(24);root.addView(title);
        message=new TextView(this);message.setText("Local timings, states and errors. Audio and conversation text are excluded. Opening this screen stops active capture.");root.addView(message);
        LinearLayout actions=new LinearLayout(this);root.addView(actions);
        add(actions,"Refresh",this::refresh);
        add(actions,"Export",()->{
            DebugLog.event("diagnostics.export","User requested report document");
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE,"translator-debug-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date())+".txt"),71);
        });
        add(actions,"Copy summary",()->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Translator diagnostics",DebugLog.summary(this)));message.setText("Summary copied. Export contains the full event timeline.");});
        LinearLayout other=new LinearLayout(this);root.addView(other);
        add(other,"Mark test start",()->{DebugLog.event("test.marker","User marked a reproduction attempt");refresh();});
        add(other,"Clear",()->new AlertDialog.Builder(this).setMessage("Clear saved diagnostic history?").setPositiveButton("Clear",(d,w)->{DebugLog.clear();refresh();}).setNegativeButton("Keep",null).show());
        ScrollView scroll=new ScrollView(this);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));timeline=new TextView(this);timeline.setTextSize(12);timeline.setTypeface(android.graphics.Typeface.MONOSPACE);timeline.setTextIsSelectable(true);scroll.addView(timeline);
        refresh();
    }
    private void add(LinearLayout row,String title,Runnable action){Button button=new Button(this);button.setAllCaps(false);button.setText(title);button.setOnClickListener(v->action.run());row.addView(button,new LinearLayout.LayoutParams(0,-2,1));}
    private void refresh(){worker.execute(()->{String full=DebugLog.report(getApplicationContext());runOnUiThread(()->{if(isDestroyed())return;report=full;timeline.setText(full.length()>150000?DebugLog.summary(this)+"\n[Showing newest events; Export includes full history]\n"+full.substring(full.length()-150000):full);});});}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=71||result!=RESULT_OK||data==null||data.getData()==null)return;
        android.net.Uri destination=data.getData();
        worker.execute(()->{
            try(OutputStream output=getContentResolver().openOutputStream(destination,"wt")){
                if(output==null)throw new IOException("Could not open export destination");output.write(DebugLog.report(getApplicationContext()).getBytes(StandardCharsets.UTF_8));
                runOnUiThread(()->{if(!isDestroyed())message.setText("Debug report saved. Attach that .txt file here to investigate the failed step.");});
            }catch(Exception e){DebugLog.error("diagnostics.export",e);runOnUiThread(()->{if(!isDestroyed())message.setText("Export failed: "+e.getMessage());});}
        });
    }
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
}
