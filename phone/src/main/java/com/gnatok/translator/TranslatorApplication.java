package com.gnatok.translator;
public final class TranslatorApplication extends android.app.Application {
    @Override public void onCreate(){super.onCreate();DebugLog.initialize(this);}
}
