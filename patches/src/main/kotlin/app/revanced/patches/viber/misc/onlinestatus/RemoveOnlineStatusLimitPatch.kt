package app.revanced.patches.viber.misc.onlinestatus

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch

@Suppress("unused")
val removeOnlineStatusLimitPatch = bytecodePatch(
    name = "Remove online status limit",
    description = "(PORTED) Removes the 24-hour wait between online status changes. " +
        "Works with Viber 23.x to 29.x.",
) {
    compatibleWith("com.viber.voip")

    apply {
        val limits = findOnlineStatusLimits()

        if (limits.isEmpty()) {
            throw PatchException("Could not find the online status limit. This Viber version is not supported.")
        }

        limits.forEach { limit ->
            // The last change is treated as if it was done a long time ago.
            firstMethod(limit.method).addInstructions(
                limit.moveResultIndex + 1,
                "const-wide/16 v${limit.register}, 0x0",
            )
        }
    }
}
