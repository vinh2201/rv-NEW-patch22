package app.revanced.patches.viber.misc

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod

@Suppress("unused")
val secondaryViberDevicePatch = bytecodePatch(
    name = "Secondary Viber Device",
    description = "Forces Viber to detect the device as a tablet, enabling the 'Link as secondary device' flow.",
) {
    compatibleWith("com.viber.voip")

    execute {
        // 1. Quét tìm TẤT CẢ các method có lệnh lấy smallestScreenWidthDp hoặc screenLayout
        val methods = classes.flatMap { it.methods }
            .filterIsInstance<MutableMethod>()
            .filter { m ->
                m.implementation?.instructions?.any {
                    it.opcode == Opcode.IGET &&
                    (it as? ReferenceInstruction)?.reference?.let { ref ->
                        ref is FieldReference && ref.definingClass == "Landroid/content/res/Configuration;" &&
                        (ref.name == "smallestScreenWidthDp" || ref.name == "screenLayout")
                    } == true
                } == true
            }

        if (methods.isEmpty()) error("Target methods for device configuration not found in Viber APK")

        // 2. Chèn lệnh giả mạo thông số vào ngay sau mỗi lần đọc
        methods.forEach { m ->
            val instructions = m.implementation!!.instructions
            
            // Duyệt danh sách index ngược (reversed) để khi chèn instruction mới vào sẽ không làm sai lệch index của các instruction phía trên
            val targetIndices = instructions.mapIndexedNotNull { index, instr ->
                if (instr.opcode == Opcode.IGET) {
                    val ref = (instr as? ReferenceInstruction)?.reference as? FieldReference
                    if (ref?.definingClass == "Landroid/content/res/Configuration;") {
                        when (ref.name) {
                            "smallestScreenWidthDp" -> Pair(index, "smallestWidth")
                            "screenLayout" -> Pair(index, "screenLayout")
                            else -> null
                        }
                    } else null
                } else null
            }.reversed()

            targetIndices.forEach { (idx, type) ->
                val igetInstr = instructions[idx] as TwoRegisterInstruction
                val destReg = igetInstr.registerA // Lấy thanh ghi đích mà Viber vừa dùng để chứa thông số

                if (type == "smallestWidth") {
                    // Ép thông số DPI thành 600 ngay trong thanh ghi đích
                    m.addInstructions(
                        idx + 1,
                        """
                        const/16 v$destReg, 0x258
                        """.trimIndent()
                    )
                } else if (type == "screenLayout") {
                    // Xóa mask size cũ (-16) và đè cờ XLARGE (0x04) qua toán tử bitwise để không làm hỏng cờ xoay màn hình (orientation)
                    m.addInstructions(
                        idx + 1,
                        """
                        and-int/lit8 v$destReg, v$destReg, -0x10
                        or-int/lit8 v$destReg, v$destReg, 0x04
                        """.trimIndent()
                    )
                }
            }
        }
    }
}
