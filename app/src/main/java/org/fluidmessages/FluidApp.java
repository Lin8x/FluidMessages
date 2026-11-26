package org.fluidmessages;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.SystemClock;

/**
 * Starts before anything else in the app, so every connection can check Connect through Tor (TorRoute). It also
 * notes when the app went to the background, so Tor inside the app (BuiltInTor) can stop once nothing needs it.
 */
public final class FluidApp extends Application {
    private static volatile int started;
    /** When the last screen left, or 0 while one is showing. */
    private static volatile long backgroundSince = SystemClock.elapsedRealtime();

    @Override public void onCreate() {
        super.onCreate();
        TorRoute.remember(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity activity) {
                started++;
                backgroundSince = 0;
            }

            @Override public void onActivityStopped(Activity activity) {
                started = Math.max(0, started - 1);
                if (started == 0) backgroundSince = SystemClock.elapsedRealtime();
            }

            @Override public void onActivityCreated(Activity activity, Bundle saved) {}
            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }

    /** How long the app has been in the background (0 while it's on screen). */
    static long backgroundFor() {
        long since = backgroundSince;
        return since == 0 ? 0 : SystemClock.elapsedRealtime() - since;
    }
}
