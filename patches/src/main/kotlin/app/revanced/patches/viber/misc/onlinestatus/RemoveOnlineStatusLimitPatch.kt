package app.revanced.patches.viber.misc.onlinestatus

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import java.util.logging.Logger

@Suppress("unused")
val removeOnlineStatusLimitPatch = bytecodePatch(
    name = "Remove online status limit",
    description = "(PORTED) Removes the 24-hour wait between online status changes. " +
        "Only available in Viber 29.x and newer, older versions don't have this limit.",
) {
    compatibleWith("com.viber.voip"("29.3.1.0"))

    apply {
        val limits = findOnlineStatusLimits()

        if (limits.isEmpty()) {
            Logger.getLogger(this::class.java.name)
                .info("The online status limit does not exist in this Viber version. Nothing to patch.")
            return@apply
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
