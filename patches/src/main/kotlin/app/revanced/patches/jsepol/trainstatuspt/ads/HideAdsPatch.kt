package app.revanced.patches.jsepol.trainstatuspt.ads

import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.forEachInstructionAsSequence
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.util.MethodUtil

@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = "Hide ads",
    use = true
) {
    compatibleWith("com.jsepol.trainstatuspt")

    apply {
        val loadAdRef = ImmutableMethodReference(
            "Lcom/google/android/gms/ads/AdView;",
            "loadAd",
            listOf("Lcom/google/android/gms/ads/AdRequest;"),
            "V"
        )

        forEachInstructionAsSequence(
            match = { _, _, instruction, index ->
                val refInstruction = instruction as? ReferenceInstruction ?: return@forEachInstructionAsSequence null
                val ref = refInstruction.reference as? MethodReference ?: return@forEachInstructionAsSequence null

                if (MethodUtil.methodSignaturesMatch(ref, loadAdRef)) index else null
            },
            transform = { mutableMethod, targetIndex ->
                mutableMethod.replaceInstruction(targetIndex, "nop")
            }
        )
    }
}
