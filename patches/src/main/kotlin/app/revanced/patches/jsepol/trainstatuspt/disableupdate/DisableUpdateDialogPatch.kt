package app.revanced.patches.jsepol.trainstatuspt.disableupdate

import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.forEachInstructionAsSequence
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Suppress("unused")
val disableUpdateDialogPatch = bytecodePatch(
    name = "Disable update dialog",
    description = "Disables the app update dialog on startup.",
    use = false
) {
    compatibleWith("com.jsepol.trainstatuspt")

    apply {
        forEachInstructionAsSequence(
            match = { classDef, _, instruction, index ->
                if (classDef.type != "Lcom/jsepol/trainstatuspt/MainActivity;") return@forEachInstructionAsSequence null

                val refInstruction = instruction as? ReferenceInstruction ?: return@forEachInstructionAsSequence null
                val ref = refInstruction.reference as? MethodReference ?: return@forEachInstructionAsSequence null

                if (ref.name == "showNewVersionDialog") index else null
            },
            transform = { mutableMethod, targetIndex ->
                mutableMethod.replaceInstruction(targetIndex, "nop")
            }
        )
    }
}