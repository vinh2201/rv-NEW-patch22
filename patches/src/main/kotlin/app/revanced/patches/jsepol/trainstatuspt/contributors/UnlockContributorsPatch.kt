package app.revanced.patches.jsepol.trainstatuspt.contributors

import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.jsepol.trainstatuspt.shared.initClassDef
import app.revanced.patches.jsepol.trainstatuspt.shared.trainDetailsClassDef
import app.revanced.util.returnEarly
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction


@Suppress("unused")
val unlockContributorsPatch = bytecodePatch(
    name = "Unlock contributors",
    description = "Forces the contributor flag to true.",
    use = true,
) {
    compatibleWith("com.jsepol.trainstatuspt")

    apply {
        initClassDef.methods.forEach { method ->
            val implementation = method.implementation ?: return@forEach
            val indices = implementation.instructions.mapIndexedNotNull { index, instruction ->
                if (instruction.opcode == Opcode.IGET_BOOLEAN &&
                    instruction.fieldReference?.name == CONTRIBUIDOR_FIELD_NAME
                ) index else null
            }
            indices.asReversed().forEach { index ->
                val register = method.getInstruction<OneRegisterInstruction>(index).registerA
                method.replaceInstruction(index, "const/16 v$register, 0x1")
            }
        }

        trainDetailsClassDef.methods.forEach { method ->
            val implementation = method.implementation ?: return@forEach
            val indices = implementation.instructions.mapIndexedNotNull { index, instruction ->
                if (instruction.opcode == Opcode.IGET_OBJECT &&
                    instruction.fieldReference?.name == CONTRIBUIDOR_FIELD_NAME &&
                    instruction.fieldReference?.type == "Ljava/lang/Boolean;"
                ) index else null
            }
            indices.asReversed().forEach { index ->
                val register = method.getInstruction<OneRegisterInstruction>(index).registerA
                method.replaceInstruction(
                    index,
                    "sget-object v$register, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;",
                )
            }
        }

        bloquearMenusMethod.returnEarly()
    }
}
