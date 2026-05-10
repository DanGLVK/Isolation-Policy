package io.github.mhmrdd.isolationpolicy;

import android.util.Log;

import de.robv.android.xposed.XposedBridge;

public class Logger {

    public static void i(String msg) {
        Log.i(Constants.TAG, msg);
        try { XposedBridge.log("[" + Constants.TAG + "] " + msg); } catch (Throwable ignored) {}
    }

    public static void w(String msg) {
        Log.w(Constants.TAG, msg);
        try { XposedBridge.log("[" + Constants.TAG + "][W] " + msg); } catch (Throwable ignored) {}
    }

    public static void e(String msg, Throwable t) {
        Log.e(Constants.TAG, msg, t);
        try { XposedBridge.log("[" + Constants.TAG + "][E] " + msg + " : " + t); } catch (Throwable ignored) {}
        try { XposedBridge.log(t); } catch (Throwable ignored) {}
    }
}
