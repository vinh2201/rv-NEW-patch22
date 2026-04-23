package app.revanced.patches.twitch.ad.audio

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch

@Suppress("unused")
val blockAudioAdsPatch = bytecodePatch(
    name = "Block audio ads",
    description = "Blocks audio ads in streams and VODs (best-effort on version 29).",
) {
    compatibleWith("tv.twitch.android.app"("16.9.1", "25.3.0", "29.0.3"))

    apply {
        audioAdSdaMetadataMethod?.addInstructions(0, "return-void")
        audioAdsPlayMethod?.addInstructions(0, "return-void")
    }
}