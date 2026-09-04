package app.revanced.patches.viber.misc

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod

@Suppress("unused")
val secondaryViberDevicePatch = bytecodePatch(
    name = "Secondary Viber Device",
    description = "Forces Viber to detect the device as a tablet, enabling the 'Link as secondary device' flow.",
) {
    compatibleWith("com.viber.voip")

    execute {
        // 1. Tìm method dựa trên hành vi (truy cập getConfiguration và smallestScreenWidthDp)
        val method = classes.flatMap { it.methods }
            .filterIsInstance<MutableMethod>()
            .firstOrNull { m ->
                val instrs = m.implementation?.instructions ?: return@firstOrNull false

                val hasGetConfig = instrs.any {
                    it.opcode == Opcode.INVOKE_VIRTUAL &&
                    (it as? ReferenceInstruction)?.reference?.let { ref ->
                        ref is MethodReference && ref.name == "getConfiguration" && ref.definingClass == "Landroid/content/res/Resources;"
                    } == true
                }

                val hasSmallestWidth = instrs.any {
                    it.opcode == Opcode.IGET &&
                    (it as? ReferenceInstruction)?.reference?.let { ref ->
                        ref is FieldReference && ref.name == "smallestScreenWidthDp" && ref.definingClass == "Landroid/content/res/Configuration;"
                    } == true
                }

                hasGetConfig && hasSmallestWidth
            } ?: error("Target method for device configuration not found in Viber APK")

        val instructions = method.implementation!!.instructions

        // 2. Tìm instruction gọi getConfiguration và trích xuất thanh ghi chứa object trả về
        val getConfigIdx = instructions.indexOfFirst {
            it.opcode == Opcode.INVOKE_VIRTUAL &&
            (it as? ReferenceInstruction)?.reference?.name == "getConfiguration"
        }

        val moveResultInstr = instructions.getOrNull(getConfigIdx + 1) as? OneRegisterInstruction
            ?: error("move-result-object instruction not found after getConfiguration")
        val configReg = moveResultInstr.registerA

        // 3. Mượn tạm thanh ghi đích của lệnh iget để xử lý math (đảm bảo an toàn không ghi đè data khác)
        val igetIdx = instructions.indexOfFirst {
            it.opcode == Opcode.IGET &&
            (it as? ReferenceInstruction)?.reference?.name == "smallestScreenWidthDp"
        }
        val igetInstr = instructions[igetIdx] as TwoRegisterInstruction
        val tempReg = igetInstr.registerA

        // 4. Inject Smali đã fix lỗi logic bytecode
        method.addInstructions(
            getConfigIdx + 2,
            """
            # Ép smallestScreenWidthDp thành 600 (0x258)
            const/16 v$tempReg, 0x258
            iput v$tempReg, v$configReg, Landroid/content/res/Configuration;->smallestScreenWidthDp:I
            
            # Ép screenLayout thành XLarge (0x04)
            iget v$tempReg, v$configReg, Landroid/content/res/Configuration;->screenLayout:I
            and-int/lit8 v$tempReg, v$tempReg, -0x10
            or-int/lit8 v$tempReg, v$tempReg, 0x04
            iput v$tempReg, v$configReg, Landroid/content/res/Configuration;->screenLayout:I
            """.trimIndent()
        )
    }
}
