package app.revanced.patches.tiktok.misc.clipboard

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch

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
        // getText() -> return null
        // TikTok's wrapper reads clipboard via getPrimaryClip().
        // Returning null means no data is ever read.
        clipboardGetTextMethod.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return-object v0
            """,
        )

        // hasText() -> return false
        // Makes the clipboard appear empty so TikTok never attempts to read it.
        clipboardHasTextMethod.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """,
        )
    }
}
