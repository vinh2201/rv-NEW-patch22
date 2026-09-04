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
    description = "Global screen width spoofing via smallestScreenWidthDp field interception using TwoRegisterInstruction.",
) {
    compatibleWith("com.viber.voip")

    execute {
        var hookedCount = 0

        classes.forEach { classDef ->
            if (!classDef.type.startsWith("Lcom/viber/")) return@forEach

            classDef.methods.forEach { method ->
                val mutableMethod = method as? MutableMethod ?: return@forEach
                val impl = mutableMethod.implementation as? MutableMethodImplementation ?: return@forEach
                val instructions = impl.instructions

                var i = 0
                while (i < instructions.size) {
                    val insn = instructions[i]

                    if (insn.opcode == Opcode.IGET || insn.opcode == Opcode.IGET_OBJECT) {
                        val fieldRef = (insn as? ReferenceInstruction)?.reference as? FieldReference
                        
                        if (fieldRef?.definingClass == "Landroid/content/res/Configuration;" &&
                            fieldRef.name == "smallestScreenWidthDp" &&
                            fieldRef.type == "I"
                        ) {
                            // Dùng TwoRegisterInstruction để bắt chính xác thanh ghi đích vA của lệnh iget
                            val twoRegInsn = insn as? TwoRegisterInstruction
                            if (twoRegInsn != null) {
                                val targetReg = twoRegInsn.registerA

                                mutableMethod.addInstructions(
                                    i + 1,
                                    """
                                    const/16 v$targetReg, 0x320
                                    """.trimIndent()
                                )
                                hookedCount++
                            }
                        }
                    }
                    i++
                }
            }
        }

        check(hookedCount > 0) {
            "Patch thất bại: Không tìm thấy điểm đọc smallestScreenWidthDp nào trong bytecode!"
        }
    }
}
