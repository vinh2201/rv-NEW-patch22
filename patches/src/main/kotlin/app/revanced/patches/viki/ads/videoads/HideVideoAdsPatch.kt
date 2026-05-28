package app.revanced.patches.viki.ads.videoads

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch

@Suppress("unused")
val hideVideoAdsPatch = bytecodePatch("Hide video ads") {
    compatibleWith("com.viki.android")

    apply {
        shouldLoadVideoAdsMethod.addInstructions(
            0,
            """
                const/4 p0, 0x0
                return p0
            """,
        )
    }
}
