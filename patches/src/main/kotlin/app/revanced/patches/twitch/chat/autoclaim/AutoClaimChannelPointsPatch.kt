package app.revanced.patches.twitch.chat.autoclaim

import app.revanced.patcher.classDef
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.instructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.all.misc.resources.addResources
import app.revanced.patches.all.misc.resources.addResourcesPatch
import app.revanced.patches.shared.misc.mapping.ResourceType
import app.revanced.patches.shared.misc.mapping.resourceMappingPatch
import app.revanced.patches.shared.misc.settings.preference.SwitchPreference
import app.revanced.patches.twitch.misc.settings.PreferenceScreen
import app.revanced.patches.twitch.misc.settings.settingsPatch
import app.revanced.util.indexOfFirstLiteralInstruction
import com.android.tools.smali.dexlib2.Opcode

internal var icClaimResourceId = -1L
    private set

private val autoClaimChannelPointsResourcePatch = resourcePatch {
    dependsOn(resourceMappingPatch)

    apply {
        icClaimResourceId = ResourceType.DRAWABLE["ic_claim"]
    }
}

@Suppress("unused")
val autoClaimChannelPointsPatch = bytecodePatch(
    name = "Auto claim channel points",
    description = "Automatically claim Channel Points.",
) {
    dependsOn(
        settingsPatch,
        addResourcesPatch,
        autoClaimChannelPointsResourcePatch,
    )

    compatibleWith("tv.twitch.android.app")

    apply {
        addResources("twitch", "chat.autoclaim.autoClaimChannelPointsPatch")

        PreferenceScreen.CHAT.GENERAL.addPreferences(
            SwitchPreference("revanced_auto_claim_channel_points"),
        )

        communityPointsButtonRenderMethod.apply {
            val buttonField = classDef.fields.firstOrNull { it.type == "Landroid/view/ViewGroup;" }?.name
                ?: throw Exception("Could not find claim button view field in $definingClass")

            val claimIconIndex = indexOfFirstLiteralInstruction(icClaimResourceId)

            if (claimIconIndex == -1)
                throw Exception("Could not find ic_claim resource in bytecode")

            val returnOffset = instructions.drop(claimIconIndex).indexOfFirst {
                it.opcode == Opcode.RETURN_VOID || it.opcode == Opcode.RETURN_VOID_NO_BARRIER
            }

            if (returnOffset == -1)
                throw Exception("Could not find claim branch return in bytecode")

            val insertIndex = claimIconIndex + returnOffset

            addInstructions(
                insertIndex,
                """
                    move-object/from16 v0, p0
                    iget-object v0, v0, $definingClass->$buttonField:Landroid/view/ViewGroup;
                    invoke-static { v0 }, Lapp/revanced/extension/twitch/patches/AutoClaimChannelPointsPatch;->autoClaim(Landroid/view/View;)V
                """,
            )
        }
    }
}
