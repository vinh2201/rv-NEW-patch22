package app.revanced.patches.googlemaps.misc.fix.settingsmenu

import app.revanced.patcher.custom
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Matches the method that sets up the profile avatar disc in the search bar.
 * This method is responsible for adding the SelectedAccountDisc view to the
 * search_omnibox_one_google_account_disc ViewGroup container. When GmsCore
 * doesn't provide InAppReach data, the disc is null and the container stays empty/hidden.
 *
 * Identified by "GmmSelectedAccountDiscController.setupAccountDiscView".
 */
internal val BytecodePatchContext.setupAccountDiscMethod by gettingFirstMethodDeclaratively(
    "GmmSelectedAccountDiscController.setupAccountDiscView",
) {
    returnType("V")
    parameterTypes("Landroid/view/ViewGroup;", "Z")
}

/**
 * Matches the static factory method that creates a SWITCH_ACCOUNTS dialog fragment.
 * The method takes (int, String) and returns the dialog fragment with a bundle
 * containing "signOutMode" and "switchToAccountName" keys.
 *
 * Identified by "SWITCH_ACCOUNTS" and "switchToAccountName".
 */
internal val BytecodePatchContext.switchAccountsDialogFactoryMethod by gettingFirstMethodDeclaratively(
    "SWITCH_ACCOUNTS",
    "switchToAccountName",
) {
    custom {
        AccessFlags.STATIC.isSet(accessFlags) &&
            parameterTypes.size == 2 &&
            parameterTypes[0] == "I" &&
            parameterTypes[1] == "Ljava/lang/String;"
    }
}

/**
 * Matches a method in the Maps settings fragment class.
 * The settings fragment implements SharedPreferences.OnSharedPreferenceChangeListener
 * and contains many settings-related dependency injection fields.
 *
 * Identified by "settingsVeneer".
 */
internal val BytecodePatchContext.settingsFragmentMethod by gettingFirstMethodDeclaratively(
    "settingsVeneer",
)
