package app.revanced.extension.twitch.patches;

import androidx.fragment.app.DialogFragment;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.twitch.settings.Settings;

@SuppressWarnings("unused")
public class UpsellPatch {
    /**
     * Dismisses an upsell dialog (Turbo / add-email) shown at app start, when enabled.
     * Called from the fragment's {@code onCreate}, before the dialog is shown.
     */
    public static void maybeDismiss(DialogFragment fragment) {
        if (fragment == null || !Settings.HIDE_UPSELL_DIALOGS.get()) {
            return;
        }

        fragment.dismissAllowingStateLoss();
        Logger.printDebug(() -> "Dismissed upsell dialog: " + fragment.getClass().getSimpleName());
    }
}
