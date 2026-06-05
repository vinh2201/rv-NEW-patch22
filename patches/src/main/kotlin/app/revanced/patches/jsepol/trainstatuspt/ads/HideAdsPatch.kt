package app.revanced.patches.jsepol.trainstatuspt.ads

import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.forEachInstructionAsSequence

@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = "Hide ads",
    use = true
) {
    compatibleWith("com.jsepol.trainstatuspt")

    apply {
        forEachInstructionAsSequence(
            match = match@{ classDef, _, instruction, instructionIndex ->
                if (classDef.type in arrayOf(MOBILE_ADS_CLASS, AD_VIEW_CLASS)) return@match null

                val reference = instruction.methodReference ?: return@match null
                if (reference.definingClass !in arrayOf(MOBILE_ADS_CLASS, AD_VIEW_CLASS)) return@match null
                if (reference.returnType != "V") return@match null

                instructionIndex
            },
            transform = { method, index ->
                method.replaceInstruction(index, "nop")
            },
        )
    }
}
