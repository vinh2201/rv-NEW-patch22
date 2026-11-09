package app.revanced.patches.viber.layout.folders

import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.returnEarly

private const val MAX_FOLDERS = 10

@Suppress("unused")
val raiseFolderLimitPatch = bytecodePatch(
    name = "Raise folder limit",
    description = "(PORTED) Allows up to $MAX_FOLDERS chat folders. Works with Viber 23.x, 26.x and 29.x.",
) {
    compatibleWith("com.viber.voip")

    apply {
        val maxFoldersMethod = findMaxFoldersMethod()
            ?: throw PatchException("Could not find the chat folders limit. This Viber version is not supported.")

        // Viber's default is 5 folders. The value is never lower than the default, so a constant is enough.
        firstMethod(maxFoldersMethod).returnEarly(MAX_FOLDERS)
    }
}
