package app.revanced.patches.twitch.ad.expandable

import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.firstClassDefOrNull
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.all.misc.resources.addResources
import app.revanced.patches.all.misc.resources.addResourcesPatch
import app.revanced.patches.shared.misc.settings.preference.SwitchPreference
import app.revanced.patches.twitch.misc.extension.sharedExtensionPatch
import app.revanced.patches.twitch.misc.settings.PreferenceScreen
import app.revanced.patches.twitch.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element


private const val EXPANDABLE_ADS_ACTIVITY =
    "tv.twitch.android.feature.expandable.ads.activity.ExpandableAdsActivity"
private const val EXPANDABLE_ADS_ACTIVITY_DESC =
    "Ltv/twitch/android/feature/expandable/ads/activity/ExpandableAdsActivity;"

@Suppress("unused")
val disableExpandableAdsResourcePatch = resourcePatch(
    name = "disable expandable ads (manifest)",
    description = "Marks ExpandableAdsActivity as disabled and non-exported in the manifest.",
    use = false,
) {
    compatibleWith("tv.twitch.android.app")

    apply {
        document("AndroidManifest.xml").use { document ->
            val activities = document.getElementsByTagName("activity")
            for (i in 0 until activities.length) {
                val node = activities.item(i) as? Element ?: continue
                val name = node.getAttribute("android:name")
                if (name == EXPANDABLE_ADS_ACTIVITY) {
                    node.setAttribute("android:enabled", "false")
                    node.setAttribute("android:exported", "false")
                }
            }
        }
    }
}

@Suppress("unused")
val disableExpandableAdsPatch = bytecodePatch(
    name = "Hide expandable ads",
    description = "Forces ExpandableAdsActivity to finish() immediately on create on Twitch v29+.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
    )

    compatibleWith("tv.twitch.android.app")

    apply {
        addResources("twitch", "ad.expandable.expandableAdsPatch")

        PreferenceScreen.ADS.SURESTREAM.addPreferences(
            SwitchPreference("revanced_hide_expandable_ads"),
        )

        val activity = firstClassDefOrNull(EXPANDABLE_ADS_ACTIVITY_DESC) ?: return@apply

        val onCreate = activity.methods.firstOrNull {
            it.name == "onCreate" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0].toString() == "Landroid/os/Bundle;" &&
                it.returnType == "V"
        } ?: return@apply

        if (onCreate.implementation == null) return@apply
        val bridgeRef: MethodReference = onCreate.implementation!!.instructions
            .firstNotNullOfOrNull { ins ->
                if (ins.opcode != Opcode.INVOKE_VIRTUAL) return@firstNotNullOfOrNull null
                val ref = (ins as? ReferenceInstruction)?.reference as? MethodReference
                    ?: return@firstNotNullOfOrNull null
                if (ref.definingClass != EXPANDABLE_ADS_ACTIVITY_DESC) return@firstNotNullOfOrNull null
                if (ref.parameterTypes.size != 1) return@firstNotNullOfOrNull null
                if (ref.parameterTypes[0].toString() != "Landroid/os/Bundle;") return@firstNotNullOfOrNull null
                if (ref.returnType != "V") return@firstNotNullOfOrNull null
                ref
            } ?: return@apply

        val gate =
            "Lapp/revanced/extension/twitch/patches/VideoAdsPatch;->shouldHideExpandableAds()Z"






        onCreate.addInstructionsWithLabels(
            0,
            .trimIndent(),
            ExternalLabel("revanced_expandable_continue", onCreate.getInstruction(0)),
        )
    }
}
