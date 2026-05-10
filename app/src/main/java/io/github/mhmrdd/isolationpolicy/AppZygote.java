package io.github.mhmrdd.isolationpolicy;

import android.app.ZygotePreload;
import android.content.pm.ApplicationInfo;
import android.system.Os;

import java.io.BufferedReader;
import java.io.FileReader;

public final class AppZygote implements ZygotePreload {

    static volatile String preloadCtx = null;
    static volatile long preloadAtMillis = 0L;

    @Override
    public void doPreload(ApplicationInfo appInfo) {
        preloadAtMillis = System.currentTimeMillis();
        preloadCtx = readSelfContext();
    }

    static String readSelfContext() {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/self/attr/current"));
            String line = r.readLine();
            if (line == null) return null;
            return line.replace("\u0000", "").trim();
        } catch (Throwable t) {
            return null;
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) {}
        }
    }

    static int currentUid() {
        try { return Os.getuid(); } catch (Throwable t) { return -1; }
    }
}
