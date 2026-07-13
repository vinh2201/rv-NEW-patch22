package app.revanced.patches.twitch.misc.upsell

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.instructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.all.misc.resources.addResources
import app.revanced.patches.all.misc.resources.addResourcesPatch
import app.revanced.patches.shared.misc.settings.preference.SwitchPreference
import app.revanced.patches.twitch.misc.extension.sharedExtensionPatch
import app.revanced.patches.twitch.misc.settings.PreferenceScreen
import app.revanced.patches.twitch.misc.settings.settingsPatch

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/revanced/extension/twitch/patches/UpsellPatch;"

@Suppress("unused")
val hideUpsellsPatch = bytecodePatch(
    name = "Hide upsells",
    description = "Suppresses the Turbo and add-email upsell dialogs shown at app start.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
    )

    compatibleWith("tv.twitch.android.app")

    apply {
        addResources("twitch", "misc.upsell.hideUpsellsPatch")

        PreferenceScreen.LAYOUT.GENERAL.addPreferences(
            SwitchPreference("revanced_hide_upsell_dialogs"),
        )

        listOf(turboUpsellOnCreateMethod, addEmailUpsellOnCreateMethod).forEach { method ->
            method.addInstructions(
                method.instructions.lastIndex,
                "invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->maybeDismiss(Landroidx/fragment/app/DialogFragment;)V",
            )
        }
    }
}
