package app.revanced.extension.youtube.patches;

import app.revanced.extension.youtube.settings.Settings;
import app.revanced.extension.youtube.settings.YouTubeActivityHook;

@SuppressWarnings("unused")
public class FixPreferenceIconPatch {
    private static final boolean REMOVE_BROKEN_PREFERENCE_ICON =
            Settings.RESTORE_OLD_SETTINGS_MENUS.get() || !YouTubeActivityHook.useBoldIcons(true);

    /**
     * Injection point.
     */
    public static boolean removePreferenceIcon() {
        return REMOVE_BROKEN_PREFERENCE_ICON;
    }
}
