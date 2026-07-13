package app.revanced.patches.twitch.misc.hide

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.instructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.all.misc.resources.addResources
import app.revanced.patches.all.misc.resources.addResourcesPatch
import app.revanced.patches.shared.misc.settings.preference.SwitchPreference
import app.revanced.patches.twitch.misc.settings.PreferenceScreen
import app.revanced.patches.twitch.misc.settings.settingsPatch

@Suppress("unused")
val hideElementsPatch = bytecodePatch(
    name = "Hide layout elements",
    description = "Adds various options to toggle the visibility layout elements.",
) {
    dependsOn(
        settingsPatch,
        addResourcesPatch,
    )

    compatibleWith("tv.twitch.android.app")

    apply {
        addResources("twitch", "misc.hide.hideElementsPatch")

        PreferenceScreen.LAYOUT.GENERAL.addPreferences(
            SwitchPreference("revanced_hide_gift_leaderboards"),
            SwitchPreference("revanced_hide_bits_button"),
            SwitchPreference("revanced_hide_emotes_button"),
            SwitchPreference("revanced_hide_chat_header"),
            SwitchPreference("revanced_hide_community_points_button"),
            SwitchPreference("revanced_hide_chat_restrictions"),
            SwitchPreference("revanced_hide_clip_button"),
            SwitchPreference("revanced_hide_cast_button"),
            SwitchPreference("revanced_hide_share_button"),
            SwitchPreference("revanced_hide_subscribe_follow_bar"),
            SwitchPreference("revanced_hide_upsell_banners"),
            SwitchPreference("revanced_hide_stories"),
            SwitchPreference("revanced_hide_predictions"),
            SwitchPreference("revanced_hide_polls"),
            SwitchPreference("revanced_hide_goals"),
            SwitchPreference("revanced_hide_drops"),
            SwitchPreference("revanced_hide_celebrations"),
            SwitchPreference("revanced_hide_raids"),
            SwitchPreference("revanced_hide_whispers"),
            SwitchPreference("revanced_hide_tooltips"),
            SwitchPreference("revanced_hide_viewer_count"),
        )

        PreferenceScreen.ADS.GENERAL.addPreferences(
            SwitchPreference("revanced_hide_ad_container"),
            SwitchPreference("revanced_hide_pbyp_ad"),
        )

        baseViewDelegateConstructorMethod.apply {
            val insertIndex = instructions.lastIndex

            addInstructions(
                insertIndex,
                "invoke-static { p2 }, Lapp/revanced/extension/twitch/patches/HideElementsPatch;->hideElements(Landroid/view/View;)V",
            )
        }
    }
}
