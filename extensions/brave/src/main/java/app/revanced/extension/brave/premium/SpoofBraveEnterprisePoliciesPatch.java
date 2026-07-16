package app.revanced.extension.brave.premium;

import android.content.Context;
import android.os.Bundle;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;

public class SpoofBraveEnterprisePoliciesPatch {
    private static final Map<String, String> DISABLED_POLICIES = new HashMap<>();
    private static final Map<String, String> ENABLED_POLICIES = new HashMap<>();

    static {
        // Disabled-style policies: toggle OFF (false) -> inject policy as true
        DISABLED_POLICIES.put("news_switch", "BraveNewsDisabled");
        DISABLED_POLICIES.put("rewards_switch", "BraveRewardsDisabled");
        DISABLED_POLICIES.put("vpn_switch", "BraveVPNDisabled");
        DISABLED_POLICIES.put("wallet_switch", "BraveWalletDisabled");

        // Enabled-style policies: toggle OFF (false) -> inject policy as false
        ENABLED_POLICIES.put("leo_ai_switch", "BraveAIChatEnabled");
        ENABLED_POLICIES.put("web_discovery_project_switch", "BraveWebDiscoveryEnabled");
        ENABLED_POLICIES.put("privacy_preserving_analytics_switch", "BraveP3AEnabled");
        ENABLED_POLICIES.put("statistics_reporting_switch", "MetricsReportingEnabled");
    }

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
