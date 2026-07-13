package app.revanced.patches.twitch.chat.style

import app.revanced.patcher.classDef
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.instructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.all.misc.resources.addResources
import app.revanced.patches.all.misc.resources.addResourcesPatch
import app.revanced.patches.shared.misc.settings.preference.InputType
import app.revanced.patches.shared.misc.settings.preference.SwitchPreference
import app.revanced.patches.shared.misc.settings.preference.TextPreference
import app.revanced.patches.twitch.misc.extension.sharedExtensionPatch
import app.revanced.patches.twitch.misc.settings.PreferenceScreen
import app.revanced.patches.twitch.misc.settings.settingsPatch

@Suppress("unused")
val chatMessageStylePatch = bytecodePatch(
    name = "Custom chat message style",
    description = "Adds options to customize the padding around chat messages.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
    )

    // Fingerprint matches an exact opcode sequence (structural, not name/string-anchored) — the
    // only patch in this set that does. Unvalidated against other builds; stays pinned until re-verified.
    compatibleWith("tv.twitch.android.app"("29.7.1"))

    apply {
        addResources("twitch", "chat.style.chatMessageStylePatch")

        PreferenceScreen.CHAT.GENERAL.addPreferences(
            SwitchPreference("revanced_chat_message_custom_padding"),
            TextPreference("revanced_chat_message_padding_top", inputType = InputType.NUMBER),
            TextPreference("revanced_chat_message_padding_bottom", inputType = InputType.NUMBER),
            TextPreference("revanced_chat_message_padding_left", inputType = InputType.NUMBER),
            TextPreference("revanced_chat_message_padding_right", inputType = InputType.NUMBER),
        )

        chatMessageViewHolderBindMethod.apply {
            val containerField = classDef.fields.firstOrNull { it.type == "Landroid/view/View;" }?.name
                ?: throw Exception("Could not find chat message container view field in $definingClass")

            val insertIndex = instructions.lastIndex

            addInstructions(
                insertIndex,
                """
                    move-object/from16 v0, p0
                    iget-object v0, v0, $definingClass->$containerField:Landroid/view/View;
                    invoke-static { v0 }, Lapp/revanced/extension/twitch/patches/ChatMessageStylePatch;->applyPadding(Landroid/view/View;)V
                """,
            )
        }
    }
}
