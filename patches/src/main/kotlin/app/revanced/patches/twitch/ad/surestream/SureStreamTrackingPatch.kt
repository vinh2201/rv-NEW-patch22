package app.revanced.patches.twitch.ad.surestream

import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.twitch.misc.extension.sharedExtensionPatch
import app.revanced.patches.twitch.misc.settings.settingsPatch



@Suppress("unused")
val blockSureStreamTrackingPatch = bytecodePatch(
    name = "Block SureStream tracking",
    description = "Defensively suppresses SureStream ad-tracking pings on Twitch v29+ by " +
        "rewriting their URLs to a non-routable blackhole address.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith("tv.twitch.android.app")

    apply {
        val wrapper = sureStreamFireTrackingUrlWrapperMethod ?: return@apply

        if (wrapper.implementation == null) return@apply

        wrapper.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, Lapp/revanced/extension/twitch/patches/VideoAdsPatch;->shouldBlockVideoAds()Z
                move-result v0
                if-eqz v0, :revanced_surestream_passthrough
                const-string p1, "https://0.0.0.0/blocked"
                :revanced_surestream_passthrough
            """.trimIndent(),
            ExternalLabel(
                "revanced_surestream_passthrough",
                wrapper.getInstruction(0),
            ),
        )
    }
}
