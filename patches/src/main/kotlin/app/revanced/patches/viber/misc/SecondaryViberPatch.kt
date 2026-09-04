package app.revanced.patches.viber.misc

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

@Suppress("unused")
val secondaryViberDevicePatch = bytecodePatch(
    name = "Secondary Viber Device",
    description = "Forces Viber to detect device as secondary tablet and bypasses phone telephony check.",
) {
    compatibleWith("com.viber.voip")

    execute {
        var configHookCount = 0
        var telephonyHookCount = 0

        classes.forEach { classDef ->
            if (!classDef.type.startsWith("Lcom/viber/") && !classDef.type.startsWith("Lcom/google/")) return@forEach

            classDef.methods.forEach { method ->
                val mutableMethod = method as? MutableMethod ?: return@forEach
                val impl = mutableMethod.implementation as? MutableMethodImplementation ?: return@forEach

                // 1. CHẶN IS TABLET
                if ((mutableMethod.name.contains("isTablet", ignoreCase = true) || mutableMethod.name == "isTablet") && mutableMethod.returnType == "Z") {
                    impl.instructions.clear()
                    mutableMethod.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
                }

                val instructions = impl.instructions
                var i = 0
                while (i < instructions.size) {
                    val insn = instructions[i]

                    if (insn.opcode == Opcode.INVOKE_VIRTUAL || insn.opcode == Opcode.INVOKE_VIRTUAL_RANGE) {
                        val methodRef = (insn as? ReferenceInstruction)?.reference as? MethodReference
                        
                        // 2. CHẶN GETCONFIGURATION
                        if (methodRef?.definingClass == "Landroid/content/res/Resources;" && methodRef.name == "getConfiguration") {
                            for (offset in 1..2) {
                                if (i + offset < instructions.size) {
                                    val nextInsn = instructions[i + offset]
                                    if (nextInsn.opcode == Opcode.MOVE_RESULT_OBJECT) {
                                        val regInsn = nextInsn as? OneRegisterInstruction
                                        if (regInsn != null) {
                                            val targetReg = regInsn.registerA
                                            val tempReg = if (targetReg == 0) 1 else 0

                                            mutableMethod.addInstructions(
                                                i + offset + 1,
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
                                            break
                                        }
                                    }
                                }
                            }
                        }

                        // 3. CHẶN KIỂM TRA SÓNG TELEPHONY (Ép hasSystemFeature("android.hardware.telephony") -> false)
                        if (methodRef?.name == "hasSystemFeature" && methodRef.returnType == "Z") {
                            if (i > 0) {
                                val prevInsn = instructions[i - 1]
                                if (prevInsn.opcode == Opcode.CONST_STRING || prevInsn.opcode == Opcode.CONST_STRING_JUMBO) {
                                    val strRef = (prevInsn as? ReferenceInstruction)?.reference as? StringReference
                                    if (strRef?.string == "android.hardware.telephony") {
                                        for (offset in 1..2) {
                                            if (i + offset < instructions.size) {
                                                val nextInsn = instructions[i + offset]
                                                if (nextInsn.opcode == Opcode.MOVE_RESULT) {
                                                    val regInsn = nextInsn as? OneRegisterInstruction
                                                    if (regInsn != null) {
                                                        val targetReg = regInsn.registerA
                                                        mutableMethod.addInstructions(
                                                            i + offset + 1,
                                                            "const/4 v$targetReg, 0x0"
                                                        )
                                                        telephonyHookCount++
                                                        break
                                                    }
                                                }
                                            }
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

        // Bắt buộc kiểm tra: Nếu không hook được điểm nào thì cho CLI văng lỗi ngay
        check(configHookCount > 0 || telephonyHookCount > 0) {
            "Patch thất bại: Không thể tiêm code vào bất kỳ vị trí getConfiguration hoặc Telephony nào trong Viber!"
        }
    }
}
