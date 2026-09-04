package app.revanced.patches.viber.misc

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch

// Class duy nhất thuộc wrapper của ReVanced
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod

// Tất cả các class/interface dexlib2 gốc
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Suppress("unused")
val secondaryViberDevicePatch = bytecodePatch(
    name = "Secondary Viber Device",
    description = "Forces Viber to detect the device as a tablet, enabling the 'Link as secondary device' flow.",
) {
    compatibleWith("com.viber.voip")

    execute {
        // LỚP 1: Ép tất cả hàm isTablet trong com.viber và com.google luôn trả về true
        classes.filter { 
            it.type.startsWith("Lcom/viber/") || it.type.startsWith("Lcom/google/")
        }.forEach { classDef ->
            classDef.methods.filter { 
                (it.name.contains("isTablet", ignoreCase = true) || it.name == "isTablet") && 
                it.returnType == "Z" 
            }.forEach { method ->
                val mutableMethod = method as? MutableMethod ?: return@forEach
                val impl = mutableMethod.implementation as? MutableMethodImplementation ?: return@forEach
                
                impl.instructions.clear()
                mutableMethod.addInstructions(
                    0,
                    """
                    const/4 v0, 0x1
                    return v0
                    """.trimIndent()
                )
            }
        }

        // LỚP 2: Can thiệp vào kết quả trả về của getConfiguration()
        var configHookCount = 0

        classes.forEach { classDef ->
            if (!classDef.type.startsWith("Lcom/viber/") && !classDef.type.startsWith("Lcom/google/")) return@forEach

            classDef.methods.forEach { method ->
                val mutableMethod = method as? MutableMethod ?: return@forEach
                val impl = mutableMethod.implementation as? MutableMethodImplementation ?: return@forEach
                val instructions = impl.instructions

                var i = 0
                while (i < instructions.size) {
                    val insn = instructions[i]

                    if (insn.opcode == Opcode.INVOKE_VIRTUAL || insn.opcode == Opcode.INVOKE_VIRTUAL_RANGE) {
                        val methodRef = (insn as? ReferenceInstruction)?.reference as? MethodReference
                        
                        if (methodRef?.definingClass == "Landroid/content/res/Resources;" &&
                            methodRef.name == "getConfiguration" &&
                            methodRef.returnType == "Landroid/content/res/Configuration;"
                        ) {
                            if (i + 1 < instructions.size) {
                                val nextInsn = instructions[i + 1]
                                if (nextInsn.opcode == Opcode.MOVE_RESULT_OBJECT) {
                                    val regInsn = nextInsn as? OneRegisterInstruction
                                    if (regInsn != null) {
                                        val targetReg = regInsn.registerNumber
                                        
                                        // Chọn thanh ghi tạm an toàn: Nếu targetReg là v0 thì dùng v1, ngược lại dùng v0
                                        val tempReg = if (targetReg == 0) 1 else 0

                                        mutableMethod.addInstructions(
                                            i + 2,
                                            """
                                            if-nez v$targetReg, :cond_viber_tablet_$configHookCount
                                            const/16 v$tempReg, 0x258
                                            iput v$tempReg, v$targetReg, Landroid/content/res/Configuration;->smallestScreenWidthDp:I
                                            iget v$tempReg, v$targetReg, Landroid/content/res/Configuration;->screenLayout:I
                                            and-int/lit8 v$tempReg, v$tempReg, -0x10
                                            or-int/lit8 v$tempReg, v$tempReg, 0x3
                                            iput v$tempReg, v$targetReg, Landroid/content/res/Configuration;->screenLayout:I
                                            :cond_viber_tablet_$configHookCount
                                            """.trimIndent()
                                        )
                                        configHookCount++
                                    }
                                }
                            }
                        }
                    }
                    i++
                }
            }
        }
    }
}
