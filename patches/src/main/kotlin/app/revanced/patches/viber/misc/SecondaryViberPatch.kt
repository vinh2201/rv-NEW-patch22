package app.revanced.patches.viber.misc

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

@Suppress("unused")
val forceTabletRegistrationPatch = bytecodePatch(
    name = "Force Viber Tablet Registration",
    description = "Forces the registration payload to explicitly report 'tablet' device type, bypassing resource checks.",
) {
    compatibleWith("com.viber.voip")

    execute {
        var hookedCount = 0

        classes.forEach { classDef ->
            // Chỉ quét đúng class U0 chứa logic đăng ký
            if (!classDef.type.contains("registration/U0")) return@forEach

            classDef.methods.forEach { method ->
                val mutableMethod = method as? MutableMethod ?: return@forEach
                val impl = mutableMethod.implementation as? MutableMethodImplementation ?: return@forEach
                val instructions = impl.instructions

                var i = 0
                while (i < instructions.size) {
                    val insn = instructions[i]

                    // Săn lùng đoạn gọi resource check 0x7f050021 (send_tablet_device_type_on_registration)
                    if (insn.opcode == Opcode.CONST && insn.toString().contains("0x7f050021")) {
                        // Tìm vị trí câu lệnh if-eqz v0 bên dưới đoạn getBoolean để bẻ lái
                        // Hoặc ta chơi kiểu cực đoan: Ghi đè thẳng giá trị chuỗi kết quả thành "tablet" ngay tại nhánh gán
                        // Đoạn smali của bác: 
                        // const v4, 0x7f050021
                        // invoke-virtual {v0, v4}, Landroid/content/res/Resources;->getBoolean(I)Z
                        // move-result v0
                        // if-eqz v0, ...
                        
                        // Ta sẽ chèn lệnh can thiệp ngay sau move-result v0 để ép v0 luôn bằng 1 (true)
                        if (i + 2 < instructions.size) {
                            mutableMethod.addInstructions(
                                i + 3,
                                """
                                # Ép kết quả getBoolean luôn là true (1)
                                const/4 v0, 0x1
                                """.trimIndent()
                            )
                            hookedCount++
                        }
                    }
                    i++
                }
            }
        }

        check(hookedCount > 0) {
            "Patch thất bại: Không tìm thấy điểm check send_tablet_device_type_on_registration trong U0!"
        }
    }
}
