package io.github.origincts;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Refresh on a worker, never start another process or perform disk IO on an input callback. */
final class HookSettings {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "OriginCTS-settings"); thread.setDaemon(true); return thread;
    });
    private static final RefreshGate refreshGate = new RefreshGate();
    private static volatile Context context;
    private static volatile int state; // Fail closed until persisted settings have been read.
    private static volatile boolean ready;

    static boolean enabled(int feature) {
        if (!ready && context != null) refresh(false);
        return ready && FeatureState.enabled(state, feature);
    }
    static void install(ClassLoader loader, boolean system) {
        if (system) {
            HookSupport.required(XposedHelpers.findAndHookMethod(
                    "com.android.server.policy.PhoneWindowManager", loader, "systemReady", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            attach((Context) XposedHelpers.getObjectField(param.thisObject, "mContext"));
                        }
                    }));
        } else {
            HookSupport.required(XposedHelpers.findAndHookMethod(Instrumentation.class,
                    "callApplicationOnCreate", Application.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            attach((Application) param.args[0]);
                        }
                    }));
            Object current = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", loader), "currentApplication");
            if (current instanceof Context app) attach(app);
        }
    }
    static synchronized void attach(Context app) {
        if (context != null) return;
        context = app.getApplicationContext() == null ? app : app.getApplicationContext();
        try {
            context.getContentResolver().registerContentObserver(SettingsStore.URI, false,
                    new ContentObserver(new Handler(Looper.getMainLooper())) {
                        @Override public void onChange(boolean selfChange) { refresh(true); }
                    });
        } catch (Throwable failure) {
            HookEntry.log("Settings observer unavailable: " + failure);
        }
        refresh(true);
    }
    private static void refresh(boolean changed) {
        if (context == null) return;
        if (!refreshGate.begin(changed, android.os.SystemClock.uptimeMillis())) return;
        WORKER.execute(() -> {
            try (Cursor cursor = context.getContentResolver().query(SettingsStore.URI, null, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) throw new IllegalStateException("Empty settings response");
                state = cursor.getInt(0) & FeatureState.ALL;
                ready = true;
            } catch (Throwable failure) {
                ready = false;
                HookEntry.log("Settings unavailable; shortcuts disabled: " + failure);
            } finally {
                if (refreshGate.finish(ready, android.os.SystemClock.uptimeMillis())) refresh(false);
            }
        });
    }

    static final class RefreshGate {
        private boolean loading, dirty;
        private long retryAt;

        synchronized boolean begin(boolean changed, long now) {
            // Only observer notifications invalidate an in-flight read; feature checks must not loop retries.
            if (changed) dirty = true;
            if (loading || (!dirty && now < retryAt)) return false;
            loading = true;
            dirty = false;
            return true;
        }
        synchronized boolean finish(boolean success, long now) {
            retryAt = success ? 0 : now + 1000;
            loading = false;
            return dirty;
        }
    }
}
