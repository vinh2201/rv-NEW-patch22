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
    description = "Forces the registration payload to explicitly report 'tablet' device type.",
) {
    compatibleWith("com.viber.voip")

    execute {
        var hookedCount = 0

        classes.forEach { classDef ->
            // Khoanh vùng: Chỉ quét trong package registration để tăng tốc và chính xác tuyệt đối
            if (!classDef.type.contains("viber/voip/registration")) return@forEach

            classDef.methods.forEach { method ->
                val mutableMethod = method as? MutableMethod ?: return@forEach
                val impl = mutableMethod.implementation as? MutableMethodImplementation ?: return@forEach
                val instructions = impl.instructions

                // Tử huyệt: Tìm method có gọi hàm lấy thông tin phần cứng (getDeviceManufacturer)
                var isPayloadBuilder = false
                for (insn in instructions) {
                    if (insn is ReferenceInstruction && insn.reference is MethodReference) {
                        val ref = insn.reference as MethodReference
                        if (ref.name == "getDeviceManufacturer" || ref.name == "getSystemVersion") {
                            isPayloadBuilder = true
                            break
                        }
                    }
                }

                // Nếu đúng là hàm dựng Payload, tiến hành truy sát chữ "phone"
                if (isPayloadBuilder) {
                    for (i in instructions.indices.reversed()) {
                        val insn = instructions[i]
                        
                        if (insn is ReferenceInstruction && insn.reference is StringReference) {
                            val str = (insn.reference as StringReference).string
                            
                            // Tóm được chỗ nó khai báo chữ "phone"
                            if (str == "phone" && insn is OneRegisterInstruction) {
                                val targetReg = insn.registerA
                                
                                mutableMethod.addInstructions(
                                    i + 1,
                                    """
                                    # Ghi đè ngay lập tức "phone" thành "tablet"
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

        check(hookedCount > 0) {
            "Patch thất bại: Đã tìm đúng hàm dựng Payload nhưng không tóm được lệnh gán chữ 'phone'!"
        }
    }
}
