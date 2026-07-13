package app.revanced.extension.twitch.patches;

import android.view.View;

import app.revanced.extension.twitch.settings.Settings;

@SuppressWarnings("unused")
public class DisableChatHapticsPatch {
    public static void applyHapticFeedbackSetting(View view) {
        view.setHapticFeedbackEnabled(!Settings.DISABLE_CHAT_HAPTICS.get());
    }
}
