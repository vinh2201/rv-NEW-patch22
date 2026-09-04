package app.revanced.patches.viber.misc

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod

@Suppress("unused")
val secondaryViberDevicePatch = bytecodePatch(
    name = "Secondary Viber Device",
    description = "Forces Viber to detect the device as a tablet, enabling the 'Link as secondary device' flow.",
) {
    compatibleWith("com.viber.voip")

    execute {
        var patchedCount = 0

        classes.flatMap { it.methods }
            .filterIsInstance<MutableMethod>()
            .forEach { m ->
                val implementation = m.implementation ?: return@forEach
                val instructions = implementation.instructions
                val registerCount = implementation.registerCount

                // Lấy một thanh ghi tạm an toàn ở đỉnh khung local registers
                val safeTempReg = if (registerCount > 1) registerCount - 1 else 0

                // Chiến lược 1: Quét tất cả các instruction tham chiếu trực tiếp đến field của Configuration
                val fieldTargetIndices = instructions.mapIndexedNotNull { index, instr ->
                    val ref = (instr as? ReferenceInstruction)?.reference as? FieldReference
                    if (ref?.definingClass == "Landroid/content/res/Configuration;") {
                        when (ref.name) {
                            "smallestScreenWidthDp" -> Pair(index, "smallestWidth")
                            "screenLayout" -> Pair(index, "screenLayout")
                            else -> null
                        }
                    } else null
                }.reversed()

                if (fieldTargetIndices.isNotEmpty()) {
                    fieldTargetIndices.forEach { (idx, type) ->
                        if (type == "smallestWidth") {
                            m.addInstructions(
                                idx + 1,
                                """
                                const/16 v$safeTempReg, 0x258
                                iput v$safeTempReg, v$safeTempReg, Landroid/content/res/Configuration;->smallestScreenWidthDp:I
                                """.trimIndent()
                            )
                        } else if (type == "screenLayout") {
                            m.addInstructions(
                                idx + 1,
                                """
                                iget v$safeTempReg, v$safeTempReg, Landroid/content/res/Configuration;->screenLayout:I
                                and-int/lit8 v$safeTempReg, v$safeTempReg, -0x10
                                or-int/lit8 v$safeTempReg, v$safeTempReg, 0x04
                                iput v$safeTempReg, v$safeTempReg, Landroid/content/res/Configuration;->screenLayout:I
                                """.trimIndent()
                            )
                        }
                    }
                    patchedCount++
                }

                // Chiến lược 2: Quét mọi điểm gọi Resources->getConfiguration() để bẫy object Configuration trả về
                val getConfigIndices = instructions.mapIndexedNotNull { index, instr ->
                    val ref = (instr as? ReferenceInstruction)?.reference as? MethodReference
                    if (instr.opcode == Opcode.INVOKE_VIRTUAL && 
                        ref?.definingClass == "Landroid/content/res/Resources;" && 
                        ref.name == "getConfiguration") {
                        index
                    } else null
                }.reversed()

                getConfigIndices.forEach { idx ->
                    val moveResult = instructions.getOrNull(idx + 1) as? OneRegisterInstruction
                    if (moveResult != null) {
                        val configReg = moveResult.registerA
                        m.addInstructions(
                            idx + 2,
                            """
                            const/16 v$safeTempReg, 0x258
                            iput v$safeTempReg, v$configReg, Landroid/content/res/Configuration;->smallestScreenWidthDp:I
                            iget v$safeTempReg, v$configReg, Landroid/content/res/Configuration;->screenLayout:I
                            and-int/lit8 v$safeTempReg, v$safeTempReg, -0x10
                            or-int/lit8 v$safeTempReg, v$safeTempReg, 0x04
                            iput v$safeTempReg, v$configReg, Landroid/content/res/Configuration;->screenLayout:I
                            """.trimIndent()
                        )
                        patchedCount++
                    }
                }
            }

        if (patchedCount == 0) {
            error("Target methods or configuration hooks not found in Viber APK (viber version mismatch or obfuscated)")
        }
    }
}
