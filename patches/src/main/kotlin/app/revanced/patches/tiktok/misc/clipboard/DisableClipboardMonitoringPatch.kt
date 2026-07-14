package app.revanced.patches.tiktok.misc.clipboard

import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.returnEarly

@Suppress("unused")
val disableClipboardMonitoringPatch = bytecodePatch(
    name = "Disable clipboard monitoring",
    description = "Prevents TikTok from reading clipboard contents. " +
        "The clipboard always appears empty to TikTok's tracking code.",
) {
    compatibleWith(
        "com.ss.android.ugc.trill"("36.5.4"),
        "com.zhiliaoapp.musically"("36.5.4"),
    )

    apply {
        clipboardGetTextMethod.returnEarly()
        clipboardHasTextMethod.returnEarly()
    }
}
