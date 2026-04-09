package app.revanced.patches.youtube.interaction.seekbar

import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.all.misc.resources.addResources
import app.revanced.patches.all.misc.resources.addResourcesPatch
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

    execute {
        addResources("youtube", "interaction.seekbar.enableLivestreamSeekingPatch")

        PreferenceScreen.SEEKBAR.addPreferences(
            SwitchPreference("revanced_enable_livestream_seeking")
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
    }
}
