package app.revanced.patches.viber.layout.typingbanner

import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.returnEarly
import java.util.logging.Logger

@Suppress("unused")
val removeTypingPrivacyBannerPatch = bytecodePatch(
    name = "Remove typing privacy banner",
    description = "(PORTED) Removes typing privacy promotions. " +
        "Only available in Viber 29.x and newer, older versions don't have this banner.",
) {
    compatibleWith("com.viber.voip"("29.3.1.0"))

    apply {
        val eligibilityMethod = findTypingPrivacyBannerEligibilityMethod()

        if (eligibilityMethod == null) {
            Logger.getLogger(this::class.java.name)
                .info("The typing privacy banner does not exist in this Viber version. Nothing to patch.")
            return@apply
        }

        firstMethod(eligibilityMethod).returnEarly(false)
    }
}
