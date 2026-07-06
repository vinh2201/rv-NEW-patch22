package app.revanced.extension.twitch.patches;

import android.view.View;

import app.revanced.extension.twitch.settings.Settings;

@SuppressWarnings("unused")
public class DisableChatHapticsPatch {
    public static void maybeHapticFeedback(View view, int flag) {
        if (Settings.DISABLE_CHAT_HAPTICS.get()) {
            return;
        }

        view.performHapticFeedback(flag);
    }
}
