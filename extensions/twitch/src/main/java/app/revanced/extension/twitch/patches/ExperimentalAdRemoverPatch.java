package app.revanced.extension.twitch.patches;

import app.revanced.extension.twitch.settings.Settings;

@SuppressWarnings("unused")
public class ExperimentalAdRemoverPatch {
    public static boolean shouldBlockAds() {
        return Settings.BLOCK_DISPLAY_ADS.get();
    }
}
