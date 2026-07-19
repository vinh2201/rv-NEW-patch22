package app.revanced.patches.jsepol.trainstatuspt.ads

import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.util.forEachInstructionAsSequence
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = "Hide ads",
    use = true
) {
    compatibleWith("com.jsepol.trainstatuspt")

    apply {
        forEachInstructionAsSequence(
            match = { _, _, instruction, index ->
                if (instruction.opcode == Opcode.INVOKE_VIRTUAL) {
                    val ref = (instruction as ReferenceInstruction).reference as? MethodReference
                    if (ref?.definingClass == "Lcom/google/android/gms/ads/AdView;" && ref.name == "loadAd") {
                        return@forEachInstructionAsSequence index
                    }
                }
                null
            },
            transform = { mutableMethod, targetIndex ->
                mutableMethod.replaceInstruction(targetIndex, "nop")
            }
        )
    }
}
