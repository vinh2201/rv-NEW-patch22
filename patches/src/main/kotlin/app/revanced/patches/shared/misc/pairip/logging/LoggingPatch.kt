package app.revanced.patches.shared.misc.pairip.logging

import app.revanced.patcher.extensions.replaceInstructions
import app.revanced.patcher.patch.bytecodePatch
import java.util.logging.Logger

@Suppress("unused")
val enablePairipLoggingPatch = bytecodePatch(
    name = "Enable Pairip VM logging",
    description = "Enables detailed native library and VM logging for Pairipcore.",
    use = false,
) {
    apply {
        if (isDebuggingEnabledMethod == null) {
            return@apply Logger.getLogger(this::class.java.name)
                .warning("Could not find Pairip logging toggle. No changes applied.")
        }

        // Force isDebuggingEnabled to return true.
        isDebuggingEnabledMethod!!.replaceInstructions(
            0,
            """
            const/4 v0, 0x1
            return v0
            """.trimIndent()
        )
    }
}
