package app.revanced.patches.twitch.ad.monitor

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.twitch.misc.extension.sharedExtensionPatch

@Suppress("unused")
val adSuppressionMonitorPatch = bytecodePatch(
    name = "Ad-suppression runtime monitor",
    description = "Debug-only observer that logs leaked ad events, ad UI overlays, and ad-network requests after the video/audio ad patches run. Pure observer: never modifies playback or dispatch.",
    use = false,
) {
    dependsOn(
        sharedExtensionPatch,
    )

    compatibleWith("tv.twitch.android.app"("16.9.1", "25.3.0", "29.0.3"))

    apply {
        twitchApplicationOnCreateMethod.addInstructions(
            0,
            "invoke-static {}, Lapp/revanced/extension/twitch/patches/AdSuppressionMonitor;->init()V",
        )
    }
}
