package io.github.mhmrdd.isolationpolicy;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Process;

public class IsolationPolicyService extends Service {

    private final IIsolationPolicyService.Stub binder = new IIsolationPolicyService.Stub() {
        @Override
        public String getReport() {
            return buildReport();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return Process.isIsolated() ? binder : null;
    }

    private static String buildReport() {
        StringBuilder sb = new StringBuilder();
        boolean preloadRan = AppZygote.preloadAtMillis != 0L;
        sb.append("preload     : ").append(preloadRan ? "ran in app_zygote" : "did not run");
        if (preloadRan) {
            String ctx = AppZygote.preloadCtx;
            sb.append("\nctx@preload : ").append(ctx != null && !ctx.isEmpty() ? ctx : "<unknown>");
            sb.append("\npreload-at  : ").append(AppZygote.preloadAtMillis).append(" ms");
        }
        String live = AppZygote.readSelfContext();
        sb.append("\nctx@now     : ").append(live != null ? live : "<unknown>");
        sb.append("\nisolated    : ").append(Process.isIsolated());
        sb.append("\nuid         : ").append(AppZygote.currentUid());
        return sb.toString();
    }
}
