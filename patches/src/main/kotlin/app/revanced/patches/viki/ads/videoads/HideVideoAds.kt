package app.revanced.patches.viki.ads

import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.returnEarly

@Suppress("unused")
val removeVideoAdsPatch = bytecodePatch(
    name = "Remove video ads",
    description = "Disables video ads in Rakuten Viki."
) {
    compatibleWith("com.viki.android("26.1.0")")

    execute {
        videoAdsMethod.returnEarly(false)
    }
}