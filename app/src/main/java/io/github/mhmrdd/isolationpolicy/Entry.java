package io.github.mhmrdd.isolationpolicy;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Entry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (Constants.APPLICATION_ID.equals(lpparam.packageName)) {
            hookSelf(lpparam.classLoader);
            return;
        }
        if (!"android".equals(lpparam.packageName)) return;
        Logger.i("handleLoadPackage on android, processName=" + lpparam.processName);
        BindHook.install(lpparam.classLoader);
    }

    private static void hookSelf(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass(
                    "io.github.mhmrdd.isolationpolicy.ModuleStatus", cl);
            int n = XposedBridge.hookAllMethods(c, "isActive", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(Boolean.TRUE);
                }
            }).size();
            Logger.i("ModuleStatus.isActive hooked in app process count=" + n);
        } catch (Throwable t) {
            Logger.e("hookSelf failed", t);
        }
    }
}
