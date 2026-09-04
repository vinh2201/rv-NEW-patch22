package app.revanced.patches.viber.misc

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod

@Suppress("unused")
val secondaryViberDevicePatch = bytecodePatch(
    name = "Secondary Viber Device",
    description = "Forces Viber to detect the device as a tablet, enabling the 'Link as secondary device' flow.",
) {
    compatibleWith("com.viber.voip")

    execute {
        var patchedCount = 0

        classes.flatMap { it.methods }
            .filterIsInstance<MutableMethod>()
            .filter { m ->
                // Chỉ nhắm vào các method trả về Boolean (Z) và có đọc thông số màn hình
                m.returnType == "Z" && m.implementation?.instructions?.any { instr ->
                    instr.opcode == Opcode.IGET && (instr as? ReferenceInstruction)?.reference?.let { ref ->
                        ref is FieldReference &&
                        ref.definingClass == "Landroid/content/res/Configuration;" &&
                        (ref.name == "smallestScreenWidthDp" || ref.name == "screenLayout")
                    } == true
                } == true
            }
            .forEach { m ->
                val impl = m.implementation ?: return@forEach

                // Xóa toàn bộ byteCode cũ bên trong hàm và ép trả về true
                impl.instructions.clear()
                m.addInstructions(
                    0,
                    """
                    const/4 v0, 0x1
                    return v0
                    """.trimIndent()
                )
                patchedCount++
            }

        if (patchedCount == 0) {
            error("Target tablet detection methods not found in Viber APK")
        }
    }
}
