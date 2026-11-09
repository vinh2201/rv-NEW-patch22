package app.revanced.patches.viber.layout.media

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch

@Suppress("unused")
val saveDisappearingMediaPatch = bytecodePatch(
    name = "Save disappearing media",
    description = "(PORTED) Shows Save to gallery for disappearing photos and videos. " +
        "Works with Viber 23.x, 26.x and 29.x.",
) {
    compatibleWith("com.viber.voip")

    apply {
        val check = findDisappearingMediaCheck()
            ?: throw PatchException("Could not find the media menu state builder. This Viber version is not supported.")

        // The message is never treated as a disappearing message when deciding to show Save to gallery.
        firstMethod(check.method).addInstructions(
            check.moveResultIndex + 1,
            "const/16 v${check.register}, 0x0",
        )
    }
}
