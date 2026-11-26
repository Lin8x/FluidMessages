package org.fluidmessages;

import android.app.Application;

/** Starts before anything else in the app, so every connection can check Connect through Tor (TorRoute). */
public final class FluidApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        TorRoute.remember(this);
    }
}
