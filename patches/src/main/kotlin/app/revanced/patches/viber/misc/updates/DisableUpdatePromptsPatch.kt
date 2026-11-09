package app.revanced.patches.viber.misc.updates

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch

@Suppress("unused")
val disableUpdatePromptsPatch = bytecodePatch(
    name = "Disable update prompts",
    description = "(PORTED) Stops the optional Google Play update dialog. Required updates still show. " +
        "Works with Viber 23.x, 26.x and 29.x.",
) {
    compatibleWith("com.viber.voip")

    apply {
        val check = findUpdatePromptCheck()
            ?: throw PatchException("Could not find the update prompt check. This Viber version is not supported.")

        val method = firstMethod(check.method)

        // Insert from the last read to the first one, so the indexes stay valid.
        check.booleanReads.sortedByDescending { (index, _) -> index }.forEach { (index, register) ->
            method.addInstructions(index + 1, "const/16 v$register, 0x0")
        }
    }
}
