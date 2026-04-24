package app.revanced.patches.twitch.ad.playermetadata

import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.firstClassDefOrNull
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.twitch.misc.extension.sharedExtensionPatch
import app.revanced.patches.twitch.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
@Suppress("unused")
val blockPlayerMetadataAdEventsPatch = bytecodePatch(
    name = "block player metadata ad events",
    description = "filters server-side-ads (SSAI) at the ExoPlayer metadata-event " +
        "funnel, preventing the ad state machine from being triggered. Compatible " +
        "with Twitch v29+.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith("tv.twitch.android.app")

    apply {
        val gate = "Lapp/revanced/extension/twitch/patches/VideoAdsPatch;->shouldDropPlayerAdEvent()Z"

        val parser = streamMetadataParserMethod ?: return@apply
        val playerClassType = parser.definingClass

        val h0Ref = parser.implementation?.instructions?.firstNotNullOfOrNull { ins ->
            if (ins.opcode != Opcode.INVOKE_VIRTUAL) return@firstNotNullOfOrNull null
            val ref = (ins as? ReferenceInstruction)?.reference as? MethodReference
                ?: return@firstNotNullOfOrNull null
            if (ref.definingClass != playerClassType) return@firstNotNullOfOrNull null
            if (ref.returnType != "V") return@firstNotNullOfOrNull null
            if (ref.parameterTypes.size != 1) return@firstNotNullOfOrNull null
            ref
        } ?: return@apply

        val playerClass = firstClassDefOrNull(playerClassType) ?: return@apply
        val refParamType = h0Ref.parameterTypes[0].toString()
        val h0 = playerClass.methods.firstOrNull {
            it.name == h0Ref.name &&
                it.returnType == "V" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0].toString() == refParamType
        } ?: return@apply

        val adEventTypes = listOfNotNull(
            onSurestreamAdStartedClassMethod?.definingClass,
            onSurestreamAdQuartileClassMethod?.definingClass,
            onMultiformatAdRequestedClassMethod?.definingClass,
            onPbypPreflightMessageClassMethod?.definingClass,
            onTriggerUrlSetClassMethod?.definingClass,
        )
        if (adEventTypes.isEmpty()) return@apply
        val checks = buildString {
            adEventTypes.forEach { type ->
                appendLine("    instance-of v0, p1, $type")
                appendLine("    if-nez v0, :revanced_drop_ad_event")
            }
        }

        h0.addInstructionsWithLabels(
            0,
           
           .trimIndent(),
            ExternalLabel("revanced_continue_h0", h0.getInstruction(0)),
        )
    }
}
