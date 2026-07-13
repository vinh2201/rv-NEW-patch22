package app.revanced.extension.twitch.patches;

import app.revanced.extension.twitch.settings.Settings;

@SuppressWarnings("unused")
public class ShowDeletedMessagesPatch {
    public static boolean shouldShowDeletedMessages() {
        return Settings.SHOW_DELETED_MESSAGES.get();
    }
}
