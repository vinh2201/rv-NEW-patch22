package app.revanced.extension.youtube.patches;

import app.revanced.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class EnableLivestreamSeekingPatch {

    /**
     * Injection point.
     */
    public static boolean enableLivestreamSeeking(boolean original) {
        return original || Settings.ENABLE_LIVESTREAM_SEEKING.get();
    }
}
