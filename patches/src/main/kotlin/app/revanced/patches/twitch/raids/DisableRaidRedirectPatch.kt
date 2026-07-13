package app.revanced.patches.twitch.raids

import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.all.misc.resources.addResources
import app.revanced.patches.all.misc.resources.addResourcesPatch
import app.revanced.patches.shared.misc.settings.preference.SwitchPreference
import app.revanced.patches.twitch.misc.extension.sharedExtensionPatch
import app.revanced.patches.twitch.misc.settings.PreferenceScreen
import app.revanced.patches.twitch.misc.settings.settingsPatch

@Suppress("unused")
val disableRaidRedirectPatch = bytecodePatch(
    name = "Disable raid redirect",
    description = "Stops the player from automatically switching to the channel you are raided into.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
    )

    compatibleWith("tv.twitch.android.app")

    apply {
        addResources("twitch", "raids.disableRaidRedirectPatch")

        PreferenceScreen.MISC.GENERAL.addPreferences(
            SwitchPreference("revanced_disable_raid_redirect"),
        )

        forceRaidNowSecondsMethod.addInstructionsWithLabels(
            0,
            """
                invoke-static {}, Lapp/revanced/extension/twitch/patches/RaidPatch;->shouldDisableRaidRedirect()Z
                move-result v0
                if-eqz v0, :original
                const v0, 0x1e8480
                return v0
            """,
            ExternalLabel("original", forceRaidNowSecondsMethod.getInstruction(0)),
        )
    }
}
