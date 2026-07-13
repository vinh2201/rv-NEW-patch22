package app.revanced.extension.twitch.patches;

import android.util.TypedValue;
import android.view.View;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.twitch.settings.Settings;

@SuppressWarnings("unused")
public class ChatMessageStylePatch {
    // Padding is resolved once (settings + display density are constant per app run) and reused,
    // instead of re-parsing/re-converting on every chat message bind.
    private static boolean initialized;
    private static boolean enabled;
    private static int left, top, right, bottom;

    public static void applyPadding(View view) {
        if (view == null) {
            return;
        }

        if (!initialized) {
            init(view);
        }

        if (!enabled) {
            return;
        }

        // Views are recycled: skip the setPadding (and its layout pass) when already applied.
        if (view.getPaddingLeft() == left && view.getPaddingTop() == top
                && view.getPaddingRight() == right && view.getPaddingBottom() == bottom) {
            return;
        }

        view.setPadding(left, top, right, bottom);
    }

    private static void init(View view) {
        enabled = Settings.CHAT_MESSAGE_CUSTOM_PADDING.get();
        if (enabled) {
            left = dpToPx(view, Settings.CHAT_MESSAGE_PADDING_LEFT.get(), 24);
            top = dpToPx(view, Settings.CHAT_MESSAGE_PADDING_TOP.get(), 9);
            right = dpToPx(view, Settings.CHAT_MESSAGE_PADDING_RIGHT.get(), 24);
            bottom = dpToPx(view, Settings.CHAT_MESSAGE_PADDING_BOTTOM.get(), 9);
            Logger.printDebug(() -> "Cached chat message padding: l=" + left + " t=" + top + " r=" + right + " b=" + bottom);
        }
        initialized = true;
    }

    private static int dpToPx(View view, String value, int fallbackDp) {
        float dp;
        try {
            dp = Float.parseFloat(value.trim());
        } catch (Exception ex) {
            dp = fallbackDp;
        }

        if (dp < 0) {
            dp = 0;
        }

        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, view.getResources().getDisplayMetrics()));
    }
}
