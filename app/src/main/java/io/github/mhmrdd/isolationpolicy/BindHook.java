package io.github.mhmrdd.isolationpolicy;

import java.util.Collections;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class BindHook {

    private static final String HOSTING_RECORD = "com.android.server.am.HostingRecord";
    private static final String PROCESS_RECORD = "com.android.server.am.ProcessRecord";

    // mHostingZygote == 2 means app zygote; Samsung removed usesAppZygote() but kept the field.
    private static final int HOSTING_ZYGOTE_APP = 2;

    private static final XSharedPreferences sPrefs =
            new XSharedPreferences(Constants.APPLICATION_ID, Constants.PREFS_NAME);

    static {
        sPrefs.makeWorldReadable();
    }

    public static void install(ClassLoader cl) {
        try {
            Class<?> processList = XposedHelpers.findClass(
                    "com.android.server.am.ProcessList", cl);
            int n = XposedBridge.hookAllMethods(processList, "startProcessLocked", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.method == null) return;
                    if (((java.lang.reflect.Method) param.method).getReturnType() != Boolean.TYPE) return;

                    Object hr = null;
                    Object pr = null;
                    for (Object arg : param.args) {
                        if (arg == null) continue;
                        String cn = arg.getClass().getName();
                        if (hr == null && HOSTING_RECORD.equals(cn)) hr = arg;
                        else if (pr == null && PROCESS_RECORD.equals(cn)) pr = arg;
                    }
                    if (hr == null || pr == null) return;

                    if (!isAppZygote(hr)) return;

                    String pkg = resolvePackage(pr);
                    if (pkg == null) return;
                    if (!isDenied(pkg)) return;

                    param.setResult(Boolean.TRUE);
                    Logger.i("accepted startProcessLocked without app zygote fork for " + pkg);
                }
            }).size();
            Logger.i("BindHook installed on ProcessList.startProcessLocked count=" + n);
        } catch (Throwable t) {
            Logger.e("BindHook install failed", t);
        }
    }

    /**
     * Checks whether the given HostingRecord targets the app zygote.
     *
     * AOSP exposes this as usesAppZygote(), which checks mHostingZygote == 2.
     * Samsung's build removes the method but retains the field and semantics,
     * confirmed by decompiling framework.jar on this device.
     * Falls back gracefully if the field is absent on other builds.
     */
    private static boolean isAppZygote(Object hostingRecord) {
        // Try the AOSP method first — works on stock Android and some OEM builds.
        try {
            Object result = XposedHelpers.callMethod(hostingRecord, "usesAppZygote");
            return Boolean.TRUE.equals(result);
        } catch (Throwable ignored) {
        }
        // Fall back to reading mHostingZygote directly — confirmed present on this Samsung build.
        try {
            int hostingZygote = XposedHelpers.getIntField(hostingRecord, "mHostingZygote");
            return hostingZygote == HOSTING_ZYGOTE_APP;
        } catch (Throwable t) {
            Logger.e("isAppZygote: could not resolve zygote type from HostingRecord", t);
            return false;
        }
    }

    private static String resolvePackage(Object processRecord) {
        try {
            Object info = XposedHelpers.getObjectField(processRecord, "info");
            if (info == null) return null;
            Object pkg = XposedHelpers.getObjectField(info, "packageName");
            if (pkg instanceof String) {
                String s = (String) pkg;
                if (!s.isEmpty()) return s;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isDenied(String pkg) {
        if (pkg == null) return false;
        if (Constants.APPLICATION_ID.equals(pkg)) return true;
        try { sPrefs.reload(); } catch (Throwable ignored) {}
        if (!sPrefs.getFile().canRead()) {
            if (BuildConfig.DEBUG) {
                Logger.i("prefs file unreachable: " + sPrefs.getFile().getAbsolutePath());
            }
            return false;
        }
        Set<String> denied = sPrefs.getStringSet(Constants.KEY_DENIED, Collections.<String>emptySet());
        if (BuildConfig.DEBUG) {
            Logger.i("policy snapshot size=" + (denied != null ? denied.size() : 0)
                    + " checking pkg=" + pkg);
        }
        return denied != null && denied.contains(pkg);
    }
    }
