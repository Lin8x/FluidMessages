package org.fluidmessages;

import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/**
 * "Quick hide" in the phone's quick settings. One tap turns decoy mode on, even while another app is open.
 * Tapping it again opens FluidMessages to ask for the private folder passcode before showing the real inbox.
 */
public final class DecoyTile extends TileService {
    static final String END = "org.fluidmessages.END_DECOY";

    @Override public void onStartListening() { update(); }

    @Override public void onClick() {
        var prefs = getSharedPreferences("fluid", MODE_PRIVATE);
        if (!Privacy.vaultExists(prefs)) {
            open(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Toast.makeText(this, "Set up the private folder first, in Settings → Privacy", Toast.LENGTH_LONG).show();
            return;
        }
        if (Privacy.decoyMode(prefs)) {
            open(new Intent(this, MainActivity.class).setAction(END).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            return;
        }
        Privacy.setDecoyMode(prefs, true);
        Privacy.forget();
        MmsTransport.changed(this);
        update();
    }

    private void open(Intent intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 7, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else startActivityAndCollapse(intent);
    }

    private void update() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean on = Privacy.decoyMode(getSharedPreferences("fluid", MODE_PRIVATE));
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel("Quick hide");
        tile.setSubtitle(on ? "On" : "Off");
        tile.updateTile();
    }

    /** Updates the tile after decoy mode changes inside the app. */
    static void refresh(Context context) {
        TileService.requestListeningState(context, new ComponentName(context, DecoyTile.class));
    }

    /** Asks Android to add the tile to quick settings. */
    static void offer(Context context) {
        var bar = context.getSystemService(StatusBarManager.class);
        if (bar == null) return;
        bar.requestAddTileService(new ComponentName(context, DecoyTile.class), "Quick hide",
            Icon.createWithResource(context, android.R.drawable.ic_menu_view), context.getMainExecutor(), result -> {});
    }
}
