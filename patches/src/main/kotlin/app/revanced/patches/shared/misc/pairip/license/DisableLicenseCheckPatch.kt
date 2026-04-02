package app.revanced.patches.shared.misc.pairip.license

import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.patch.booleanOption
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.returnEarly
import java.util.logging.Logger

@Suppress("unused")
val disablePairipLicenseCheckPatch = bytecodePatch(
    name = "Disable Pairip license check",
    description = "Disables Play Integrity API (Pairip) client-side license check.",
    use = false,
) {
    val disableRepeatedChecks by booleanOption(
        name = "Disable background repeated checks",
        description = "Disables background Play Integrity re-verification to save battery and prevent crashes.",
        default = true,
    )

    apply {
        val logger = Logger.getLogger(this::class.java.name)
        fun logMissing(tag: String) = logger.warning("Could not find Pairip licensing method '$tag'.")

        // Set first parameter (responseCode) to 0 (success status).
        processLicenseResponseMethod?.addInstruction(0, "const/4 p1, 0x0")
            ?: logMissing("processLicenseResponseMethod")

        // Short-circuit the license response validation.
        validateLicenseResponseMethod?.returnEarly()
            ?: logMissing("validateLicenseResponseMethod")

        // Make sure the installer app is a system app (one of the pass methods).
        checkLocalInstallerMethod?.returnEarly(true)
            ?: logMissing("checkLocalInstallerMethod")

        // Optionally disable repeated background checks.
        if (disableRepeatedChecks == true) {
            licenseClientClinit?.addInstruction(
                0,
                """
                const/4 v0, 0x0
                sput-boolean v0, Lcom/pairip/licensecheck/LicenseClient;->repeatedCheckEnabled:Z
                """.trimIndent()
            ) ?: logger.warning("Could not find LicenseClient static initializer to disable repeated checks.")
        }
    }
}
