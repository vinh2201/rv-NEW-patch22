package app.revanced.extension.twitch.settings.preference;

import android.app.Dialog;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.view.View;
import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.revanced.extension.twitch.settings.Settings;
import app.revanced.extension.twitch.settings.TwitchActivityHook;

/**
 * Preference fragment for ReVanced settings.
 */
@SuppressWarnings("deprecation")
public class TwitchPreferenceFragment extends AbstractPreferenceFragment {

    @Override
    protected void initialize() {
        super.initialize();

        // Do anything that forces this apps Settings bundle to load.
        if (Settings.BLOCK_DISPLAY_ADS.get()) {
            Logger.printDebug(() -> "Display ad remover enabled"); // Any statement that references the app settings.
        }

        Bundle arguments = getArguments();
        if (arguments != null) {
            String screenKey = arguments.getString(TwitchActivityHook.EXTRA_REVANCED_SCREEN);
            if (screenKey != null) {
                Preference preference = findPreference(screenKey);
                if (preference instanceof PreferenceScreen) {
                    setPreferenceScreen((PreferenceScreen) preference);
                }
            }
        }
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (preference instanceof PreferenceScreen && preference.getKey() != null) {
            Dialog dialog = ((PreferenceScreen) preference).getDialog();
            if (dialog != null && dialog.getWindow() != null) {
                View decorView = dialog.getWindow().getDecorView();
                decorView.setOnApplyWindowInsetsListener((v, insets) -> {
                    v.setPadding(
                            v.getPaddingLeft(),
                            insets.getSystemWindowInsetTop(),
                            v.getPaddingRight(),
                            insets.getSystemWindowInsetBottom());
                    return insets;
                });
                decorView.requestApplyInsets();
            } else {
                Logger.printDebug(() -> "onPreferenceTreeClick nested dialog not available for insetting");
            }

            TwitchActivityHook.openRevancedScreen(getActivity(), preference.getKey());
            return true;
        }

        return super.onPreferenceTreeClick(preferenceScreen, preference);
    }
}
