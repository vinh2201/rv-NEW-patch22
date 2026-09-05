package app.revanced.patches.viber.misc

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Suppress("unused")
val secondaryViberDevicePatch = bytecodePatch(
    name = "Secondary Viber Device",
    description = "Exact Morphe logic port: Forces Viber to detect device as a tablet via getConfiguration bytecode manipulation.",
) {
    compatibleWith("com.viber.voip")

    execute {
        var hookedCount = 0

        classes.forEach { classDef ->
            if (!classDef.type.startsWith("Lcom/viber/")) return@forEach

            classDef.methods.forEach { method ->
                val mutableMethod = method as? MutableMethod ?: return@forEach
                val impl = mutableMethod.implementation as? MutableMethodImplementation ?: return@forEach
                val instructions = impl.instructions

                var i = 0
                while (i < instructions.size) {
                    val insn = instructions[i]

                    if (insn.opcode == Opcode.INVOKE_VIRTUAL) {
                        val methodRef = (insn as? ReferenceInstruction)?.reference as? MethodReference
                        if (methodRef?.definingClass == "Landroid/content/res/Resources;" &&
                            methodRef.name == "getConfiguration"
                        ) {
                            if (i + 1 < instructions.size) {
                                val nextInsn = instructions[i + 1]
                                if (nextInsn.opcode == Opcode.MOVE_RESULT_OBJECT) {
                                    val regInsn = nextInsn as? OneRegisterInstruction
                                    if (regInsn != null) {
                                        val configReg = regInsn.registerA
                                        
                                        val tempReg = if (configReg == 1) 2 else 1

                                        if (impl.registerCount <= maxOf(configReg, tempReg)) {
                                            i++
                                            continue
                                        }

                                        // Sử dụng const/16 cho giá trị 0x0f để không bị vượt ngưỡng giới hạn của const/4
                                        mutableMethod.addInstructions(
                                            i + 2,
                                            """
                                            const/16 v$tempReg, 0x258
                                            iput v$tempReg, v$configReg, Landroid/content/res/Configuration;->smallestScreenWidthDp:I
                                            
                                            const/16 v$tempReg, 0x0f
                                            iget v2, v$configReg, Landroid/content/res/Configuration;->screenLayout:I
                                            and-int/2addr v2, v$tempReg
                                            if-gez v2, :cond_viber_tablet_0
                                            not-int v$tempReg, v$tempReg
                                            and-int/2addr v$configReg, v$tempReg
                                            const/4 v$tempReg, 0x02
                                            or-int/2addr v$configReg, v$tempReg
                                            :cond_viber_tablet_0
                                            """.trimIndent()
                                        )
                                        hookedCount++
                                    }
                                }
                            }
                        }
                    }
                    i++
                }
            }
        }

        check(hookedCount > 0) {
            "Patch thất bại: Không tìm thấy bất kỳ điểm gọi Resources.getConfiguration() nào trong các class của Viber để áp dụng logic Morphe!"
        }
    }
}
