package app.revanced.patches.jsepol.trainstatuspt.disableupdate

import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.returnEarly

@Suppress("unused")
val disableUpdateDialogPatch = bytecodePatch(
    name = "Disable update dialog",
    description = "Disables the app update dialog on startup.",
    use = true
) {
    compatibleWith("com.jsepol.trainstatuspt")

    apply {
        showNewVersionDialogMethod.returnEarly()
    }
}