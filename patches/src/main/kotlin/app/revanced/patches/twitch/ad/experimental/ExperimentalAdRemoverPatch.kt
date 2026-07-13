package app.revanced.patches.twitch.ad.experimental

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.all.misc.resources.addResources
import app.revanced.patches.all.misc.resources.addResourcesPatch
import app.revanced.patches.shared.misc.settings.preference.SwitchPreference
import app.revanced.patches.twitch.misc.extension.sharedExtensionPatch
import app.revanced.patches.twitch.misc.settings.PreferenceScreen
import app.revanced.patches.twitch.misc.settings.settingsPatch

private const val RETURN_VOID = "return-void"
private const val RETURN_NULL = "const/4 v0, 0x0\nreturn-object v0"
private const val RETURN_ZERO = "const/4 v0, 0x0\nreturn v0"

@Suppress("unused")
val experimentalAdRemoverPatch = bytecodePatch(
    name = "Experimental Ad Remover",
    description = "Experimentally removes display and banner ads by neutralizing ad container and response methods.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
    )

    compatibleWith("tv.twitch.android.app")

    apply {
        addResources("twitch", "ad.experimental.remover")

        PreferenceScreen.ADS.GENERAL.addPreferences(
            SwitchPreference("revanced_block_display_ads"),
        )

        fun MutableMethod.gateReturn(returnInstructions: String) {
            if (implementation == null) return

            addInstructionsWithLabels(
                0,
                "invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->shouldBlockAds()Z\n" +
                    "move-result v0\n" +
                    "if-eqz v0, :original\n" +
                    returnInstructions,
                ExternalLabel("original", getInstruction(0)),
            )
        }

        getTreatmentAndRecordTriggerMethod.gateReturn(RETURN_NULL)
        getAdViewMethod.gateReturn(RETURN_NULL)
        onMeasureMethod.gateReturn(RETURN_VOID)
        checkerAd1Method.gateReturn(RETURN_NULL)
        getAccessTokenMethod.gateReturn(RETURN_NULL)
        getAccessToken2Method.gateReturn(RETURN_NULL)
        onActivityResultMethod.gateReturn(RETURN_VOID)
        getMaxHeightPxMethod.gateReturn(RETURN_ZERO)
        getMaxWidthPxMethod.gateReturn(RETURN_ZERO)
        getAdHeightPxMethod.gateReturn(RETURN_NULL)
        getAdWidthPxMethod.gateReturn(RETURN_NULL)
        getHeightMethod.gateReturn(RETURN_ZERO)
        getWidthMethod.gateReturn(RETURN_ZERO)
    }
}
