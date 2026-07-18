package app.revanced.extension.brave.premium;

import android.content.Context;
import android.os.Bundle;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import android.content.SharedPreferences;
import java.util.Map;

public class SpoofBraveEnterprisePoliciesPatch {
    private static final Map<String, String> DISABLED_POLICIES = Map.of(
        "news_switch", "BraveNewsDisabled",
        "rewards_switch", "BraveRewardsDisabled",
        "vpn_switch", "BraveVPNDisabled",
        "wallet_switch", "BraveWalletDisabled"
    );

    private static final Map<String, String> ENABLED_POLICIES = Map.of(
        "leo_ai_switch", "BraveAIChatEnabled",
        "web_discovery_project_switch", "BraveWebDiscoveryEnabled",
        "privacy_preserving_analytics_switch", "BraveP3AEnabled",
        "statistics_reporting_switch", "MetricsReportingEnabled"
    );

    public static Bundle getSpoofedRestrictions(Context context) {
        StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskReads();
        try {
            Bundle bundle = new Bundle();
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

            for (Map.Entry<String, String> entry : DISABLED_POLICIES.entrySet()) {
                if (!prefs.getBoolean(entry.getKey(), false)) {
                    bundle.putBoolean(entry.getValue(), true);
                }
            }

            for (Map.Entry<String, String> entry : ENABLED_POLICIES.entrySet()) {
                if (!prefs.getBoolean(entry.getKey(), false)) {
                    bundle.putBoolean(entry.getValue(), false);
                }
            }

            return bundle;
        } finally {
            StrictMode.setThreadPolicy(oldPolicy);
        }
    }
}
