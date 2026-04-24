package app.revanced.extension.twitch.patches;

import app.revanced.extension.twitch.settings.Settings;

@SuppressWarnings("unused")
public class VideoAdsPatch {
    public static boolean shouldBlockVideoAds() {
        return Settings.BLOCK_VIDEO_ADS.get();
    }

    public static String redirectTrackingUrlIfBlocked(String originalUrl) {
        if (Settings.BLOCK_VIDEO_ADS.get()) {
            return "https://0.0.0.0/blocked";
        }
        return originalUrl;
    }
    public static boolean shouldDropPlayerAdEvent() {
        return Settings.BLOCK_VIDEO_ADS.get();
    }

    public static boolean shouldDropPubSubMidroll() {
        return Settings.BLOCK_PUBSUB_MIDROLL.get();
    }
    public static boolean shouldHideExpandableAds() {
        return Settings.HIDE_EXPANDABLE_ADS.get();
    }
}