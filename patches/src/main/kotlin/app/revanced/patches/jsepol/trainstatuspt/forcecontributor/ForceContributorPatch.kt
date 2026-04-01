package app.revanced.patches.jsepol.trainstatuspt.forcecontributor

import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.forEachInstructionAsSequence
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Suppress("unused")
val forceContributorPatch = bytecodePatch(
    name = "Force contributor",
    description = "Enables the contributor flag, unlocking exclusive features.",
    use = true,
) {
    compatibleWith("com.jsepol.trainstatuspt")

    apply {
        forEachInstructionAsSequence(
            match = { classDef, _, instruction, index ->
                val className = classDef.type

                if (className.startsWith("Lcom/jsepol/trainstatuspt/MainActivity")) {
                    val ref = (instruction as? ReferenceInstruction)?.reference as? FieldReference
                    if (ref?.name == "contribuidor") {
                        val twoRegInstr = instruction as? TwoRegisterInstruction ?: return@forEachInstructionAsSequence null

                        if (instruction.opcode == Opcode.IPUT_BOOLEAN) {
                            return@forEachInstructionAsSequence Triple(0, index, twoRegInstr.registerA)
                        } else if (instruction.opcode == Opcode.IGET_BOOLEAN) {
                            return@forEachInstructionAsSequence Triple(3, index, twoRegInstr.registerA)
                        }
                    }
                }

                if (className.startsWith("Lcom/jsepol/trainstatuspt/TrainDetailsActivity")) {
                    val ref = (instruction as? ReferenceInstruction)?.reference

                    if (instruction.opcode == Opcode.IGET_OBJECT && (ref as? FieldReference)?.name == "contribuidor") {
                        val twoRegInstr = instruction as? TwoRegisterInstruction ?: return@forEachInstructionAsSequence null
                        return@forEachInstructionAsSequence Triple(1, index, twoRegInstr.registerA)
                    }

                    if (instruction.opcode == Opcode.INVOKE_DIRECT && (ref as? MethodReference)?.name == "bloquearMenus") {
                        val fiveRegInstr = instruction as? FiveRegisterInstruction ?: return@forEachInstructionAsSequence null
                        return@forEachInstructionAsSequence Triple(2, index, fiveRegInstr.registerC)
                    }
                }
                null
            },
            transform = { mutableMethod, (type, targetIndex, register) ->
                when (type) {
                    0 -> mutableMethod.addInstruction(targetIndex, "const/4 v$register, 0x1")
                    1 -> mutableMethod.replaceInstruction(targetIndex, "sget-object v$register, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;")
                    2 -> mutableMethod.replaceInstruction(targetIndex, "invoke-direct {v$register}, Lcom/jsepol/trainstatuspt/TrainDetailsActivity;->desbloquearMenus()V")
                    3 -> mutableMethod.replaceInstruction(targetIndex, "const/4 v$register, 0x1")
                }
            }
        )
    }
}
