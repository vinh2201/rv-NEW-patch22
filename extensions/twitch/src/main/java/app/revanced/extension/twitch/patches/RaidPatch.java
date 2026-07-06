package app.revanced.extension.twitch.patches;

import app.revanced.extension.twitch.settings.Settings;

@SuppressWarnings("unused")
public class RaidPatch {
    public static boolean shouldDisableRaidRedirect() {
        return Settings.DISABLE_RAID_REDIRECT.get();
    }
}
