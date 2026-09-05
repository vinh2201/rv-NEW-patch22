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
    description = "Ported and corrected from Morphe: Intercepts getConfiguration() to spoof tablet dimensions, layout, and 200 DPI.",
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

                                        // Chọn thanh ghi tạm an toàn nằm trong giới hạn cho phép của hàm
                                        val tempReg = if (configReg == 0) 1 else 0
                                        if (tempReg >= impl.registerCount) {
                                            i++
                                            continue
                                        }

                                        mutableMethod.addInstructions(
                                            i + 2,
                                            """
                                            # 1. Ép smallestScreenWidthDp thành 600dp (0x258)
                                            const/16 v$tempReg, 0x258
                                            iput v$tempReg, v$configReg, Landroid/content/res/Configuration;->smallestScreenWidthDp:I
                                            
                                            # 2. Ép densityDpi thành 200 (0xc8) theo đúng thực tế ADB của bác
                                            const/16 v$tempReg, 0xc8
                                            iput v$tempReg, v$configReg, Landroid/content/res/Configuration;->densityDpi:I
                                            
                                            # 3. Xử lý screenLayout: Ép về kích thước Tablet (Size Large = 0x3)
                                            iget v$tempReg, v$configReg, Landroid/content/res/Configuration;->screenLayout:I
                                            and-int/lit8 v$tempReg, v$tempReg, -0x10
                                            or-int/lit8 v$tempReg, v$tempReg, 0x3
                                            iput v$tempReg, v$configReg, Landroid/content/res/Configuration;->screenLayout:I
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
            "Patch thất bại: Không tìm thấy điểm gọi getConfiguration nào trong Viber!"
        }
    }
}
