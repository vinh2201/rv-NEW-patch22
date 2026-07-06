package app.revanced.patches.twitch.chat.haptics

import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.instructions
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.all.misc.resources.addResources
import app.revanced.patches.all.misc.resources.addResourcesPatch
import app.revanced.patches.shared.misc.settings.preference.SwitchPreference
import app.revanced.patches.twitch.misc.extension.sharedExtensionPatch
import app.revanced.patches.twitch.misc.settings.PreferenceScreen
import app.revanced.patches.twitch.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val PERFORM_HAPTIC_FEEDBACK = "Landroid/view/View;->performHapticFeedback(I)Z"

@Suppress("unused")
val disableChatHapticsPatch = bytecodePatch(
    name = "Disable chat haptics",
    description = "Disables the haptic feedback vibration when interacting with chat messages.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
    )

    compatibleWith("tv.twitch.android.app")

    apply {
        addResources("twitch", "chat.haptics.disableChatHapticsPatch")

        PreferenceScreen.CHAT.GENERAL.addPreferences(
            SwitchPreference("revanced_disable_chat_haptics"),
        )

        chatMessageHapticMethod.apply {
            val index = instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_VIRTUAL &&
                    (it as? ReferenceInstruction)?.reference.toString() == PERFORM_HAPTIC_FEEDBACK
            }

            if (index == -1) {
                throw Exception("Could not find performHapticFeedback call")
            }

            val instruction = getInstruction(index) as FiveRegisterInstruction

            replaceInstruction(
                index,
                "invoke-static { v${instruction.registerC}, v${instruction.registerD} }, " +
                    "Lapp/revanced/extension/twitch/patches/DisableChatHapticsPatch;->" +
                    "maybeHapticFeedback(Landroid/view/View;I)V",
            )
        }
    }
}
