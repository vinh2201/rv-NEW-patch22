package app.revanced.patches.viber.layout.promotions

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import java.util.logging.Logger

@Suppress("unused")
val hidePromotionsPatch = bytecodePatch(
    name = "Hide Viber Out and channel promotions",
    description = "(PORTED) Hides the Viber Out banners and intro on the Calls tab and the channel and bot " +
        "suggestions in the chat list. Works with Viber 23.x, 26.x and 29.x.",
) {
    compatibleWith("com.viber.voip")

    apply {
        val logger = Logger.getLogger(this::class.java.name)
        var patched = 0

        // Channel and bot suggestions of the chat list: p1 is the visibility.
        findChatSuggestionsVisibilityMethod()?.let { method ->
            firstMethod(method).addInstructions(0, "const/4 p1, 0x0")
            patched++
        } ?: logger.warning("Could not find the chat suggestions.")

        // Viber Out section of the Calls tab. It is in the same method as the banners, and after them,
        // so it is patched first to keep the indexes of the banners valid.
        findViberOutSectionVisibility()?.let { section ->
            firstMethod(section.method).addInstructions(section.index + 1, "const/16 v${section.register}, 0x0")
            patched++
        } ?: logger.warning("Could not find the Calls tab Viber Out section.")

        // Viber Out banner and shadow of the Calls tab.
        val banners = findCallsBannerVisibility()
        if (banners.isEmpty()) {
            logger.warning("Could not find the Calls tab Viber Out banner.")
        } else {
            val method = firstMethod(banners.first().method)
            banners.sortedByDescending { it.index }.forEach { banner ->
                method.addInstructions(banner.index + 1, "const/16 v${banner.register}, 0x0")
            }
            patched++
        }

        // Calls tab introduction.
        findCallsIntroFlag()?.let { intro ->
            firstMethod(intro.method).addInstructions(intro.index + 1, "const/16 v${intro.register}, 0x0")
            patched++
        } ?: logger.warning("Could not find the Calls tab introduction.")

        if (patched == 0) {
            throw PatchException("Could not find any promotion. This Viber version is not supported.")
        }
    }
}
