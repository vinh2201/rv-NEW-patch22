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
    description = "Globally intercepts Resources.getConfiguration to force tablet mode in Viber.",
) {
    compatibleWith("com.viber.voip")

    execute {
        var globalHookCount = 0

        classes.forEach { classDef ->
            // Quét toàn bộ package chính của Viber và các thư viện liên quan
            if (!classDef.type.startsWith("Lcom/viber/") && !classDef.type.startsWith("Landroidx/")) return@forEach

            classDef.methods.forEach { method ->
                val mutableMethod = method as? MutableMethod ?: return@forEach
                val impl = mutableMethod.implementation as? MutableMethodImplementation ?: return@forEach

                val instructions = impl.instructions
                var i = 0
                while (i < instructions.size) {
                    val insn = instructions[i]

                    if (insn.opcode == Opcode.INVOKE_VIRTUAL || insn.opcode == Opcode.INVOKE_VIRTUAL_RANGE) {
                        val methodRef = (insn as? ReferenceInstruction)?.reference as? MethodReference
                        
                        // Bắt mọi lệnh gọi getConfiguration của Resources
                        if (methodRef?.definingClass == "Landroid/content/res/Resources;" && methodRef.name == "getConfiguration") {
                            for (offset in 1..3) {
                                if (i + offset < instructions.size) {
                                    val nextInsn = instructions[i + offset]
                                    if (nextInsn.opcode == Opcode.MOVE_RESULT_OBJECT) {
                                        val regInsn = nextInsn as? OneRegisterInstruction
                                        if (regInsn != null) {
                                            val targetReg = regInsn.registerA
                                            val tempReg = if (targetReg == 0) 1 else 0

                                            // Tiêm mã giả lập trực tiếp vào đối tượng Configuration vừa lấy ra
                                            mutableMethod.addInstructions(
                                                i + offset + 1,
                                                """
                                                if-nez v$targetReg, :cond_viber_global_$globalHookCount
                                                const/16 v$tempReg, 0xc8
                                                iput v$tempReg, v$targetReg, Landroid/content/res/Configuration;->smallestScreenWidthDp:I
                                                iget v$tempReg, v$targetReg, Landroid/content/res/Configuration;->screenLayout:I
                                                and-int/lit8 v$tempReg, v$tempReg, -0x10
                                                or-int/lit8 v$tempReg, v$tempReg, 0x3
                                                iput v$tempReg, v$targetReg, Landroid/content/res/Configuration;->screenLayout:I
                                                :cond_viber_global_$globalHookCount
                                                """.trimIndent()
                                            )
                                            globalHookCount++
                                            break
                                        }
                                    }
                                }
                            }
                        }
                    }
                    i++
                }
            }
        }

        check(globalHookCount > 0) {
            "Patch thất bại: Không tìm thấy bất kỳ điểm gọi getConfiguration nào trong APK!"
        }
    }
}
