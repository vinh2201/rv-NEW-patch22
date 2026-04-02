package app.revanced.patches.shared.misc.pairip.vm

import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.returnEarly
import java.util.logging.Logger

@Suppress("unused")
val disablePairipVmPatch = bytecodePatch(
    name = "Disable Pairip VM",
    description = "Prevents startup VM sequence for Pairipcore protected applications.",
    use = false,
) {
    apply {
        if (launchVMMethod == null) {
            return@apply Logger.getLogger(this::class.java.name)
                .warning("Could not find Pairip VM startup launcher. This is expected in most versions.")
        }

        // Prevent VM from loading.
        launchVMMethod!!.returnEarly()
    }
}
