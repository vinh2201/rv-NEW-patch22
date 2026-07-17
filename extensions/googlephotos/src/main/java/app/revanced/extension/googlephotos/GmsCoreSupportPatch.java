package app.revanced.extension.googlephotos;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;

import app.revanced.extension.shared.GmsCoreSupport;

@SuppressWarnings("unused")
public class GmsCoreSupportPatch {
    private static final String GOOGLE_ONE_ACCOUNT_FEATURE = "service_googleone";

    public static Account[] getGoogleAccounts(Context context) {
        return AccountManager.get(context).getAccountsByType(GmsCoreSupport.getGmsCoreAccountType());
    }

    public static Account[] getGoogleAccountsForFeatures(Context context, String[] features) {
        return features != null && features.length == 1
                && GOOGLE_ONE_ACCOUNT_FEATURE.equals(features[0])
                ? getGoogleAccounts(context)
                : null;
    }
}
