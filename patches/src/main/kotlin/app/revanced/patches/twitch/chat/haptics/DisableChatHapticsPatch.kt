package app.revanced.patches.twitch.chat.haptics

import app.revanced.patcher.classDef
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.instructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.all.misc.resources.addResources
import app.revanced.patches.all.misc.resources.addResourcesPatch
import app.revanced.patches.shared.misc.settings.preference.SwitchPreference
import app.revanced.patches.twitch.misc.extension.sharedExtensionPatch
import app.revanced.patches.twitch.misc.settings.PreferenceScreen
import app.revanced.patches.twitch.misc.settings.settingsPatch

@Suppress("unused")
val disableChatHapticsPatch = bytecodePatch(
    name = "Disable chat haptics",
    description = "Disables the haptic feedback vibration when long-pressing chat messages.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
    )

    compatibleWith("tv.twitch.android.app"("29.7.1"))

    apply {
        addResources("twitch", "chat.haptics.disableChatHapticsPatch")

        PreferenceScreen.CHAT.GENERAL.addPreferences(
            SwitchPreference("revanced_disable_chat_haptics"),
        )

        chatHapticsRowMethod.apply {
            val containerField = classDef.fields.firstOrNull { it.type == "Landroid/view/View;" }?.name
                ?: throw Exception("Could not find chat message container view field in $definingClass")

            addInstructions(
                instructions.lastIndex,
                """
                    move-object/from16 v0, p0
                    iget-object v0, v0, $definingClass->$containerField:Landroid/view/View;
                    invoke-static { v0 }, Lapp/revanced/extension/twitch/patches/DisableChatHapticsPatch;->applyHapticFeedbackSetting(Landroid/view/View;)V
                """,
            )
        }
    }
}
