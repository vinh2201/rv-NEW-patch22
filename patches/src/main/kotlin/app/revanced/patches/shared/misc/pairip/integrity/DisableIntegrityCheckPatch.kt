package app.revanced.patches.shared.misc.pairip.integrity

import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.returnEarly
import java.util.logging.Logger

@Suppress("unused")
val disablePairipIntegrityCheckPatch = bytecodePatch(
    name = "Disable Pairip integrity check",
    description = "Disables Google's Pairipcore signature and integrity checks.",
    use = false,
) {
    apply {
        if (verifyIntegrityMethod == null) {
            return@apply Logger.getLogger(this::class.java.name)
                .warning("Could not find Pairipcore integrity check. No changes applied.")
        }

        // Neutralize the signature verification method by making it return early.
        verifyIntegrityMethod!!.returnEarly()
    }
}
