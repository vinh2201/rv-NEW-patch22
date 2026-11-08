package app.revanced.patches.viber.ads

import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.booleanOption
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.returnEarly
import java.util.logging.Logger

@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = "Hide ads",
    description = "Hides ads in Viber (chat list, contacts, calls, explore, after calls...). " +
        "Works with Viber 23.x, 26.x and 29.x, and should keep working with newer versions.",
) {
    compatibleWith("com.viber.voip")

    val blockAdsController by booleanOption(
        name = "Block ads controller",
        description = "Makes the ads controller report that ads are not allowed. " +
            "This is the method that works on every version.",
        default = true,
    )

    val enableAdsFreeFlag by booleanOption(
        name = "Enable ads-free flag",
        description = "Turns on the Viber Plus \"ads free\" debug feature switch. " +
            "Only exists in older versions (23.x - 26.x), ignored when not found. " +
            "Disable this if you have problems with other screens after patching.",
        default = true,
    )

    apply {
        val logger = Logger.getLogger(this::class.java.name)
        var patched = false

        if (blockAdsController != false) {
            val adsAllowedMethod = findAdsAllowedMethod()

            if (adsAllowedMethod != null) {
                firstMethod(adsAllowedMethod).returnEarly(false)
                patched = true
            } else {
                logger.warning("Could not find the ads controller method.")
            }
        }

        if (enableAdsFreeFlag != false) {
            val adsFreeFlagMethod = findAdsFreeFlagMethod()

            if (adsFreeFlagMethod != null) {
                firstMethod(adsFreeFlagMethod).returnEarly(1)
                patched = true
            } else {
                logger.info("Ads-free flag not found in this version. Skipping.")
            }
        }

        if (!patched) {
            throw PatchException("Could not find any ads related method. This Viber version is not supported.")
        }
    }
}
