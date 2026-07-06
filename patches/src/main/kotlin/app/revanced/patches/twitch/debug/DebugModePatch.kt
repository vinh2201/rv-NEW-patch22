package app.revanced.patches.twitch.debug

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.all.misc.resources.addResources
import app.revanced.patches.all.misc.resources.addResourcesPatch
import app.revanced.patches.shared.misc.settings.preference.SwitchPreference
import app.revanced.patches.twitch.misc.extension.sharedExtensionPatch
import app.revanced.patches.twitch.misc.settings.PreferenceScreen
import app.revanced.patches.twitch.misc.settings.settingsPatch

val debugModePatch = bytecodePatch(
    name = "Debug mode",
    description = "Enables Twitch's internal debugging mode.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
    )

    compatibleWith("tv.twitch.android.app")

    apply {
        addResources("twitch", "debug.debugModePatch")

        PreferenceScreen.MISC.OTHER.addPreferences(
            SwitchPreference("revanced_twitch_debug_mode"),
        )

        classDefs
            .filter { "Ltv/twitch/android/util/DebugInfoProvider;" in it.interfaces }
            .forEach { classDef ->
                classDefs.getOrReplaceMutable(classDef).methods
                    .filter { it.name == "isEnabled" && it.returnType == "Z" && it.parameterTypes.isEmpty() }
                    .forEach { method ->
                        method.addInstructions(
                            0,
                            """
                                invoke-static {}, Lapp/revanced/extension/twitch/patches/DebugModePatch;->isDebugModeEnabled()Z
                                move-result v0
                                return v0
                            """,
                        )
                    }
            }
    }
}
