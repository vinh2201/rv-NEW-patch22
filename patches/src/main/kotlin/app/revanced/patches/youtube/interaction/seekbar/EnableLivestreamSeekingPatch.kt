package app.revanced.patches.youtube.interaction.seekbar

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.all.misc.resources.addResources
import app.revanced.patches.all.misc.resources.addResourcesPatch
import app.revanced.patches.shared.misc.settings.preference.PreferenceCategory
import app.revanced.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.revanced.patches.shared.misc.settings.preference.SwitchPreference
import app.revanced.patches.youtube.misc.extension.sharedExtensionPatch
import app.revanced.patches.youtube.misc.settings.PreferenceScreen
import app.revanced.patches.youtube.misc.settings.settingsPatch
import app.revanced.patches.youtube.misc.spoof.spoofVideoStreamsPatch
import app.revanced.util.addInstructionsAtControlFlowLabel
import app.revanced.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/youtube/patches/EnableLivestreamSeekingPatch;"

@Suppress("unused")
val enableLivestreamSeekingPatch = bytecodePatch(
    description = "Adds an option to enable seeking on live streams that have it disabled.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        spoofVideoStreamsPatch,
        addResourcesPatch,
    )

    apply {
        addResources("youtube", "interaction.seekbar.enableLivestreamSeekingPatch")

        PreferenceScreen.SEEKBAR.addPreferences(
            PreferenceCategory(
                titleKey = null,
                sorting = Sorting.UNSORTED,
                tag = "app.revanced.extension.shared.settings.preference.NoTitlePreferenceCategory",
                preferences = setOf(
                    SwitchPreference("revanced_enable_livestream_seeking"),
                    SwitchPreference("revanced_increase_livestream_seeking_duration")
                )
            )
        )

        videoStreamingDataAllowSeekingMethod.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN).forEach { returnIndex ->
                val returnRegister = getInstruction<OneRegisterInstruction>(returnIndex).registerA

                addInstructionsAtControlFlowLabel(
                    returnIndex,
                    """
                        invoke-static { v$returnRegister }, $EXTENSION_CLASS_DESCRIPTOR->enableLivestreamSeeking(Z)Z
                        move-result v$returnRegister
                    """
                )
            }
        }

        formatStreamModelMaxDvrDurationMethodMatch.let {
            it.method.apply {
                val index = it[-1]
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructions(
                    index,
                    """
                        invoke-static { v$register, v${register + 1} }, ${EXTENSION_CLASS_DESCRIPTOR}->overrideMaxSeekingDurationSeconds(D)D
                        move-result-wide v$register
                    """
                )
            }
        }
    }
}
