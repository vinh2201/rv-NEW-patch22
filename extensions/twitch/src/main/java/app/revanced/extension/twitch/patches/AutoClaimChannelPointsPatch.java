package app.revanced.extension.twitch.patches;

import android.view.View;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.twitch.settings.Settings;

@SuppressWarnings("unused")
public class AutoClaimChannelPointsPatch {
    public static void autoClaim(View buttonLayout) {
        if (!Settings.AUTO_CLAIM_CHANNEL_POINTS.get()) {
            return;
        }

        boolean handled = buttonLayout.callOnClick();
        Logger.printDebug(() -> "Auto-claim: clicked claim button (handled=" + handled + ")");
    }
}
