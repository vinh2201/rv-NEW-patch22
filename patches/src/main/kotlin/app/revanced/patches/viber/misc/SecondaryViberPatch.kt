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
    description = "Forces Viber registration to report device type as 'tablet' by intercepting phone string assignments.",
) {
    compatibleWith("com.viber.voip")

    execute {
        var hookedCount = 0

        classes.forEach { classDef ->
            classDef.methods.forEach { method ->
                val mutableMethod = method as? MutableMethod ?: return@forEach
                val impl = mutableMethod.implementation as? MutableMethodImplementation ?: return@forEach
                val instructions = impl.instructions

                var hasTablet = false
                var hasPhone = false
                instructions.forEach { insn ->
                    if (insn is ReferenceInstruction && insn.reference is StringReference) {
                        val str = (insn.reference as StringReference).string
                        if (str == "tablet") hasTablet = true
                        if (str == "phone") hasPhone = true
                    }
                }

                if (hasTablet && hasPhone) {
                    val indicesToReplace = mutableListOf<Pair<Int, Int>>()

                    for (i in instructions.indices) {
                        val insn = instructions[i]
                        if (insn is ReferenceInstruction && insn.reference is StringReference) {
                            val strRef = insn.reference as StringReference
                            if (strRef.string == "phone" && insn.opcode.name.lowercase().startsWith("const-string")) {
                                if (insn is OneRegisterInstruction) {
                                    indicesToReplace.add(i to insn.registerA)
                                }
                            }
                        }
                    }

                    // Thay thế từ dưới lên để không bị lệch index khi xóa/thêm
                    for ((i, reg) in indicesToReplace.sortedByDescending { it.first }) {
                        instructions.removeAt(i)
                        mutableMethod.addInstructions(
                            i,
                            "const-string v$reg, \"tablet\""
                        )
                        hookedCount++
                    }
                }
            }
        }

        check(hookedCount > 0) {
            "Patch thất bại: Không tìm thấy chuỗi 'phone' trong phương thức phân loại thiết bị của Viber!"
        }
    }
}
