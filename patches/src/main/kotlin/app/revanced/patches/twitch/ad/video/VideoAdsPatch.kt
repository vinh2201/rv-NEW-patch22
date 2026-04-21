package app.revanced.patches.twitch.ad.video

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
@Suppress("unused")
val blockVideoAdsPatch = bytecodePatch(
    name = "Block video ads",
    description = "Blocks server-stitched (SureStream) video ad signals in streams and VODs.",
) {
    compatibleWith("tv.twitch.android.app"("16.9.1", "25.3.0", "29.0.3"))

    apply {
        videoAdMetadataHandlerMethod.addInstructions(0, "return-void")
        midrollPubSubConsumerMethod?.addInstructions(0, "return-void")
        pbypPreflightConsumerMethod?.addInstructions(0, "return-void")
    }
}