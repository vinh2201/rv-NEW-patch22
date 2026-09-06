package app.revanced.patches.viber.misc

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

@Suppress("unused")
val forceTabletRegistrationPatch = bytecodePatch(
    name = "Secondary Viber Device",
    description = "Forces registration to report 'tablet' by forcing the resource boolean check to true.",
) {
    compatibleWith("com.viber.voip")

    execute {
        var hookedCount = 0

        classes.forEach { classDef ->
            // Khoanh vùng chính xác vào U0 (hoặc các class trong package registration)
            if (!classDef.type.contains("viber/voip/registration")) return@forEach

            classDef.methods.forEach { method ->
                val mutableMethod = method as? MutableMethod ?: return@forEach
                val impl = mutableMethod.implementation as? MutableMethodImplementation ?: return@forEach
                val instructions = impl.instructions

                // Fingerprint: Tìm xem method này có chứa cả hai chuỗi "tablet" và "phone" hay không
                var hasTablet = false
                var hasPhone = false
                instructions.forEach { insn ->
                    if (insn is ReferenceInstruction && insn.reference is StringReference) {
                        val str = (insn.reference as StringReference).string
                        if (str == "tablet") hasTablet = true
                        if (str == "phone") hasPhone = true
                    }
                }

                // Nếu đúng là hàm đăng ký chứa nhánh rẽ tablet/phone này
                if (hasTablet && hasPhone) {
                    for (i in instructions.indices) {
                        val insn = instructions[i]
                        
                        // Quét tìm dòng gọi Resources->getBoolean(I)
                        if (insn is ReferenceInstruction && insn.reference is MethodReference) {
                            val ref = insn.reference as MethodReference
                            if (ref.name == "getBoolean" && ref.definingClass.contains("android/content/res/Resources")) {
                                // Lệnh tiếp theo sau getBoolean chính là "move-result vX"
                                val moveResultIndex = i + 1
                                if (moveResultIndex < instructions.size) {
                                    val moveResultInsn = instructions[moveResultIndex]
                                    val candStr = moveResultInsn.opcode.name.lowercase()
                                    
                                    if (candStr.startsWith("move-result") && moveResultInsn is OneRegisterInstruction) {
                                        val targetReg = moveResultInsn.registerA
                                        
                                        // Chèn lệnh ép cứng vX = 0x1 (true) ngay sau move-result
                                        mutableMethod.addInstructions(
                                            moveResultIndex + 1,
                                            """
                                            # Ép kết quả getBoolean check tablet thành true (1)
                                            const/4 v$targetReg, 0x1
                                            """.trimIndent()
                                        )
                                        hookedCount++
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        check(hookedCount > 0) {
            "Patch thất bại: Không tìm thấy điểm chèn lệnh ép giá trị boolean tablet trong U0!"
        }
    }
}
