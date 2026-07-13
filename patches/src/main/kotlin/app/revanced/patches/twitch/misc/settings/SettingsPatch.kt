package app.revanced.patches.twitch.misc.settings

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.instructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.all.misc.resources.addResources
import app.revanced.patches.all.misc.resources.addResourcesPatch
import app.revanced.patches.shared.misc.settings.preference.*
import app.revanced.patches.shared.misc.settings.settingsPatch
import app.revanced.patches.twitch.misc.extension.sharedExtensionPatch
import app.revanced.patches.twitch.misc.fix.fixResourceLinkingPatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_PACKAGE = "app/revanced/extension/twitch"
private const val ACTIVITY_HOOKS_CLASS_DESCRIPTOR = "L$EXTENSION_PACKAGE/settings/TwitchActivityHook;"

private val preferences = mutableSetOf<BasePreference>()

fun addSettingPreference(screen: BasePreference) {
    preferences += screen
}

val settingsPatch = bytecodePatch(
    name = "Settings",
    description = "Adds settings menu to Twitch.",
) {
    dependsOn(
        sharedExtensionPatch,
        addResourcesPatch,
        settingsPatch(preferences = preferences),
        fixResourceLinkingPatch
    )

    compatibleWith("tv.twitch.android.app")

    apply {
        addResources("twitch", "misc.settings.settingsPatch")

        preferences += NonInteractivePreference(
            key = "revanced_about",
            tag = "app.revanced.extension.shared.settings.preference.ReVancedAboutPreference",
            selectable = true,
        )

        PreferenceScreen.MISC.OTHER.addPreferences(
            // The debug setting is shared across multiple apps and the key must be the same.
            // But the title and summary must be different, otherwise when the strings file is flattened
            // for Crowdin push, Crowdin gets confused by the duplicate keys.
            // FIXME: Ideally the shared debug strings are extracted into a common app group
            //  and then both apps import that. But for now unique unique title and summary keys also works.
            SwitchPreference(
                key = "revanced_debug",
                titleKey = "revanced_twitch_debug_title",
                summaryOnKey = "revanced_twitch_debug_summary_on",
                summaryOffKey = "revanced_twitch_debug_summary_off",
            ),
        )

        settingsActivityOnCreateMethod.apply {
            val insertIndex = instructions.lastIndex

            addInstructions(
                insertIndex,
                "invoke-static { p0 }, $ACTIVITY_HOOKS_CLASS_DESCRIPTOR->handleSettingsCreation(Landroid/app/Activity;)Z",
            )
        }

        mainSettingsFragmentOnCreateViewMethod.apply {
            val returnIndex = instructions.indexOfLast { it.opcode == Opcode.RETURN_OBJECT }
            val returnRegister = (getInstruction(returnIndex) as OneRegisterInstruction).registerA

            addInstructions(
                returnIndex,
                """
                    invoke-static { v$returnRegister }, $ACTIVITY_HOOKS_CLASS_DESCRIPTOR->wrapSettingsView(Landroid/view/View;)Landroid/view/View;
                    move-result-object v$returnRegister
                """,
            )
        }
    }

    afterDependents {
        PreferenceScreen.close()
    }
}

/**
 * Preference screens patches should add their settings to.
 */
@Suppress("ktlint:standard:property-naming")
internal object PreferenceScreen : BasePreferenceScreen() {
    val ADS = CustomScreen("revanced_ads_screen")
    val CHAT = CustomScreen("revanced_chat_screen")
    val MISC = CustomScreen("revanced_misc_screen")
    val LAYOUT = CustomScreen("revanced_layout_screen")
    val OVERRIDE = CustomScreen("revanced_override_screen")

    internal class CustomScreen(key: String) : Screen(key) {
        /* Categories */
        val GENERAL = CustomCategory("revanced_general_category")
        val OTHER = CustomCategory("revanced_other_category")
        val CLIENT_SIDE = CustomCategory("revanced_client_ads_category")
        val SURESTREAM = CustomCategory("revanced_surestream_ads_category")
        val CHANNEL = CustomCategory("revanced_channel_category")
        val CONTENT = CustomCategory("revanced_content_category")
        val MODERATION = CustomCategory("revanced_moderation_category")
        val REWARDS = CustomCategory("revanced_rewards_category")

        internal inner class CustomCategory(key: String) : Category(key) {
            /* For Twitch, we need to load our CustomPreferenceCategory class instead of the default one. */
            override fun transform(): PreferenceCategory = PreferenceCategory(
                key,
                preferences = preferences,
                tag = "app.revanced.extension.twitch.settings.preference.CustomPreferenceCategory",
            )
        }
    }

    override fun commit(screen: PreferenceScreenPreference) {
        addSettingPreference(screen)
    }
}
