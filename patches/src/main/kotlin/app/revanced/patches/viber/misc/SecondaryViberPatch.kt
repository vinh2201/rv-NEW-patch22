package app.revanced.patches.viber.misc

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

@Suppress("unused")
val forceTabletRegistrationPatch = bytecodePatch(
    name = "Secondary Viber Device",
    description = "Forces the registration payload to explicitly report 'tablet' device type by globally intercepting resource checks.",
) {
    compatibleWith("com.viber.voip")

    execute {
        var hookedCount = 0

        classes.forEach { classDef ->
            classDef.methods.forEach { method ->
                val mutableMethod = method as? MutableMethod ?: return@forEach
                val impl = mutableMethod.implementation as? MutableMethodImplementation ?: return@forEach
                val instructions = impl.instructions

                var i = 0
                while (i < instructions.size) {
                    val insn = instructions[i]

                    // Quét toàn cục tìm hằng số chứa resource ID 0x7f050021
                    val narrowLiteral = (insn as? NarrowLiteralInstruction)?.narrowLiteral
                    if (narrowLiteral == 0x7f050021) {
                        // Tự động tìm lệnh move-result / move-result-object ở các bước kế tiếp để tóm đúng thanh ghi chứa kết quả
                        var moveResultIndex = -1
                        var targetReg = 0

                        val scanLimit = minOf(i + 5, instructions.size)
                        for (j in (i + 1) until scanLimit) {
                            val candidate = instructions[j]
                            if (candidate.opcode.name.startsWith("MOVE_RESULT")) {
                                moveResultIndex = j
                                if (candidate is OneRegisterInstruction) {
                                    targetReg = candidate.registerA
                                }
                                break
                            }
                        }

                        // Nếu tìm thấy điểm hứng kết quả, bơm đè lệnh ép giá trị thành true (1) ngay lập tức
                        if (moveResultIndex != -1) {
                            mutableMethod.addInstructions(
                                moveResultIndex + 1,
                                """
                                # Ép kết quả check resource đăng ký tablet luôn là true
                                const/4 v$targetReg, 0x1
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
            "Patch thất bại: Không tìm thấy bất kỳ điểm check resource send_tablet_device_type_on_registration nào trong APK!"
        }
    }
}
