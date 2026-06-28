package app.revanced.extension.brave.premium;

import android.content.Context;
import android.os.Bundle;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import android.content.SharedPreferences;

public class SpoofBraveEnterprisePoliciesPatch {
    public static Bundle getSpoofedRestrictions(Context context) {
        StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskReads();
        try {
            Bundle bundle = new Bundle();
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

            // Disabled-style policies: only inject the key when the user has explicitly turned the toggle OFF.
            // When the toggle is ON (or the pref is absent / default=false), omit the key so
            // Chromium's native default (enabled) takes effect on restart.
            if (!prefs.getBoolean("news_switch", false))
                bundle.putBoolean("BraveNewsDisabled", true);
            if (!prefs.getBoolean("rewards_switch", false))
                bundle.putBoolean("BraveRewardsDisabled", true);
            if (!prefs.getBoolean("vpn_switch", false))
                bundle.putBoolean("BraveVPNDisabled", true);
            if (!prefs.getBoolean("wallet_switch", false))
                bundle.putBoolean("BraveWalletDisabled", true);

            // Enabled-style policies: only inject the key (as false) when the user has turned the toggle OFF.
            // When ON (or absent), omit the key so Chromium's native default (enabled) applies.
            if (!prefs.getBoolean("leo_ai_switch", false))
                bundle.putBoolean("BraveAIChatEnabled", false);
            if (!prefs.getBoolean("web_discovery_project_switch", false))
                bundle.putBoolean("BraveWebDiscoveryEnabled", false);
            if (!prefs.getBoolean("privacy_preserving_analytics_switch", false))
                bundle.putBoolean("BraveP3AEnabled", false);
            if (!prefs.getBoolean("statistics_reporting_switch", false))
                bundle.putBoolean("MetricsReportingEnabled", false);

            return bundle;
        } finally {
            StrictMode.setThreadPolicy(oldPolicy);
        }
    }
}
