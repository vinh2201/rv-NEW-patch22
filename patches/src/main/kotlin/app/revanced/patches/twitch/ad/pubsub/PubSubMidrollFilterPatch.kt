package app.revanced.patches.twitch.ad.pubsub

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
val blockPubSubMidrollPatch = bytecodePatch(
    name = "Block PubSub midroll requests",
    description = "Drops server-pushed PubSub midroll-trigger events on Twitch v29+ " +
        "before client-side ad-slot warming begins. Defense-in-depth alongside " +
        "the player metadata filter.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
    )

    compatibleWith("tv.twitch.android.app")

    apply {
        addResources("twitch", "ad.pubsub.pubsubMidrollPatch")

        PreferenceScreen.ADS.SURESTREAM.addPreferences(
            SwitchPreference("revanced_block_pubsub_midroll"),
        )

        val dispatcher = pubSubChannelAdsDispatcherInvokeMethod ?: return@apply
        val midrollType = midrollRequestTypeClassMethod?.definingClass ?: return@apply

        val gate =
            "Lapp/revanced/extension/twitch/patches/VideoAdsPatch;->shouldDropPubSubMidroll()Z"

        dispatcher.addInstructionsWithLabels(
            0,.trimIndent(),
            ExternalLabel("revanced_pubsub_continue", dispatcher.getInstruction(0)),
        )
    }
}
