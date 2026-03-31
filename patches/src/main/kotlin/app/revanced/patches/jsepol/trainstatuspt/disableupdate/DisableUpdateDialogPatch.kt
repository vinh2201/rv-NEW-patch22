package app.revanced.patches.jsepol.trainstatuspt.disableupdate

import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.util.forEachInstructionAsSequence
import com.android.tools.smali.dexlib2.Opcode
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
                if (classDef.type == "Lcom/jsepol/trainstatuspt/MainActivity;") {
                    if (instruction.opcode == Opcode.INVOKE_DIRECT) {
                        val ref = (instruction as ReferenceInstruction).reference as? MethodReference
                        if (ref?.name == "showNewVersionDialog") {
                            return@forEachInstructionAsSequence index
                        }
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
