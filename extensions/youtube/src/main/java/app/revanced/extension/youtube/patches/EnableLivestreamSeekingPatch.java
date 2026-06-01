package app.revanced.extension.youtube.patches;

import app.revanced.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class EnableLivestreamSeekingPatch {
    private static final int SEVEN_DAYS_IN_SECONDS = 7 * 24 * 60 * 60;

    /**
     * Injection point.
     */
    public static double overrideMaxDvrDurationSec(double originalDurationSec) {
        if (!Settings.INCREASE_LIVESTREAM_SEEKING_DURATION.get()) return originalDurationSec;
        if (originalDurationSec <= 0) return originalDurationSec;

        return SEVEN_DAYS_IN_SECONDS;
    }

    /**
     * Injection point.
     */
    public static boolean enableLivestreamSeeking(boolean original) {
        return original || Settings.ENABLE_LIVESTREAM_SEEKING.get();
    }
}
