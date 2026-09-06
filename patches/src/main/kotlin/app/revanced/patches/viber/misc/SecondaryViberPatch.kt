package app.revanced.patches.viber.misc

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

@Suppress("unused")
val forceTabletRegistrationPatch = bytecodePatch(
    name = "Secondary Viber Device",
    description = "Forces the registration payload to explicitly report 'tablet' device type by hard-overriding the phone string literal.",
) {
    compatibleWith("com.viber.voip")

    execute {
        var hookedCount = 0
        var foundMethod = false

        classes.forEach { classDef ->
            classDef.methods.forEach { method ->
                val mutableMethod = method as? MutableMethod ?: return@forEach
                val impl = mutableMethod.implementation as? MutableMethodImplementation ?: return@forEach
                val instructions = impl.instructions

                // Bước 1: Fingerprint - Dò đúng method chứa cả "tablet" và "phone"
                var hasTablet = false
                var hasPhone = false
                instructions.forEach { insn ->
                    if (insn is ReferenceInstruction && insn.reference is StringReference) {
                        val str = (insn.reference as StringReference).string
                        if (str == "tablet") hasTablet = true
                        if (str == "phone") hasPhone = true
                    }
                }

                // Bước 2: Chặn họng trực tiếp tại thanh ghi
                if (hasTablet && hasPhone) {
                    foundMethod = true
                    
                    // BẮT BUỘC quét ngược (reversed) để khi chèn lệnh không bị sai lệch index
                    for (i in instructions.indices.reversed()) {
                        val insn = instructions[i]
                        
                        if (insn is ReferenceInstruction && insn.reference is StringReference) {
                            val str = (insn.reference as StringReference).string
                            
                            // Nếu tìm thấy chỗ nó nạp chữ "phone"
                            if (str == "phone" && insn is OneRegisterInstruction) {
                                val targetReg = insn.registerA
                                
                                // Chèn lệnh đè chuỗi ngay bên dưới. 
                                // Nghĩa là nó vừa gán "phone" xong sẽ bị gán đè ngay lập tức thành "tablet".
                                mutableMethod.addInstructions(
                                    i + 1,
                                    """
                                    # Ghi đè trực tiếp giá trị thanh ghi v$targetReg thành "tablet"
                                    const-string v$targetReg, "tablet"
                                    """.trimIndent()
                                )
                                hookedCount++
                            }
                        }
                    }
                }
            }
        }

        check(foundMethod) {
            "Patch thất bại: Không tìm thấy method nào chứa cả hai chuỗi 'tablet' và 'phone'!"
        }
        check(hookedCount > 0) {
            "Patch thất bại: Đã tìm thấy method, nhưng không chèn được mã ép kiểu 'tablet'!"
        }
    }
}
