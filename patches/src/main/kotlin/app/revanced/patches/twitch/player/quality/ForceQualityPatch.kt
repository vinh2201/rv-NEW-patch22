package app.revanced.patches.twitch.player.quality

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
val forceQualityPatch = bytecodePatch(
    name = "Force max video quality",
    description = "Automatically selects the highest available stream quality on playback.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
    )

    compatibleWith("tv.twitch.android.app")

    apply {
        addResources("twitch", "player.quality.forceQualityPatch")

        PreferenceScreen.MISC.GENERAL.addPreferences(
            SwitchPreference("revanced_force_max_quality"),
        )

        setQualityMethod.apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { p0, p1 }, Lapp/revanced/extension/twitch/patches/ForceQualityPatch;->forceMaxQuality(Lcom/amazonaws/ivs/player/MediaPlayer;Ljava/lang/String;)Z
                    move-result v0
                    if-eqz v0, :original
                    return-void
                """,
                ExternalLabel("original", getInstruction(0)),
            )
        }
    }
}
