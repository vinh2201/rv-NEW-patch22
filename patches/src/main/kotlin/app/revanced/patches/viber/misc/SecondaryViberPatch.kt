package app.revanced.patches.viber.misc

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

@Suppress("unused")
val secondaryViberDevicePatch = bytecodePatch(
    name = "Secondary Viber Device",
    description = "Global screen width and layout spoofing (Morphe Parity).",
) {
    compatibleWith("com.viber.voip")

    execute {
        var hookedCount = 0

        classes.forEach { classDef ->
            // Chỉ quét các class của Viber để tiết kiệm thời gian
            if (!classDef.type.startsWith("Lcom/viber/")) return@forEach

            classDef.methods.forEach { method ->
                val mutableMethod = method as? MutableMethod ?: return@forEach
                val impl = mutableMethod.implementation as? MutableMethodImplementation ?: return@forEach
                val instructions = impl.instructions

                var i = 0
                while (i < instructions.size) {
                    val insn = instructions[i]

                    // Bắt các lệnh đọc dữ liệu Object (iget)
                    if (insn.opcode == Opcode.IGET || insn.opcode == Opcode.IGET_OBJECT) {
                        val fieldRef = (insn as? ReferenceInstruction)?.reference as? FieldReference
                        
                        // Nếu đang đọc dữ liệu từ Android Configuration
                        if (fieldRef?.definingClass == "Landroid/content/res/Configuration;") {
                            val twoRegInsn = insn as? TwoRegisterInstruction
                            if (twoRegInsn != null) {
                                val targetReg = twoRegInsn.registerA

                                // 1. TÁI TẠO MORPHE: Ép smallestScreenWidthDp thành 600 (0x258)
                                if (fieldRef.name == "smallestScreenWidthDp") {
                                    mutableMethod.addInstructions(
                                        i + 1,
                                        """
                                        const/16 v$targetReg, 0x258
                                        """.trimIndent()
                                    )
                                    hookedCount++
                                }
                                
                                // 2. TÁI TẠO MORPHE: Bẻ bit của screenLayout thành SIZE_LARGE (0x3)
                                if (fieldRef.name == "screenLayout") {
                                    mutableMethod.addInstructions(
                                        i + 1,
                                        """
                                        and-int/lit8 v$targetReg, v$targetReg, -0x10
                                        or-int/lit8 v$targetReg, v$targetReg, 0x3
                                        """.trimIndent()
                                    )
                                    hookedCount++
                                }
                            }
                        }
                    }
                    i++
                }
            }
        }

        check(hookedCount > 0) {
            "Patch thất bại: Không tìm thấy điểm check screen/layout nào của Viber!"
        }
    }
}
