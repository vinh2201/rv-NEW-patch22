package app.revanced.patches.viber.misc

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction

@Suppress("unused")
val forceTabletRegistrationPatch = bytecodePatch(
    name = "Secondary Viber Device",
    description = "Forces the registration payload to explicitly report 'tablet' device type by overriding registration resource checks.",
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

                    // Bắt chính xác hằng số chứa ID resource 0x7f050021
                    if (insn is NarrowLiteralInstruction && insn.narrowLiteral == 0x7f050021) {
                        var moveResultIndex = -1
                        var targetReg = 0

                        val scanLimit = minOf(i + 6, instructions.size)
                        for (j in (i + 1) until scanLimit) {
                            val candidate = instructions[j]
                            val candStr = candidate.opcode.name.lowercase()
                            
                            // HẾT ẢO GIÁC: Dalvik dùng gạch nối "move-result", không phải gạch dưới!
                            if (candStr.startsWith("move-result")) {
                                moveResultIndex = j
                                if (candidate is OneRegisterInstruction) {
                                    targetReg = candidate.registerA
                                }
                                break
                            }
                        }

                        if (moveResultIndex != -1) {
                            mutableMethod.addInstructions(
                                moveResultIndex + 1,
                                """
                                # Ép kết quả getBoolean luôn trả về true (1)
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
            "Patch thất bại: Đã tìm thấy resource 0x7f050021 nhưng không tìm thấy lệnh move-result đi kèm!"
        }
    }
}
