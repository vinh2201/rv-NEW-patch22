package app.revanced.patches.viber.misc

import app.revanced.patcher.patch.bytecodePatch
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

@Suppress("unused")
val forceTabletRegistrationPatch = bytecodePatch(
    name = "Secondary Viber Device",
    description = "Forces registration by globally overriding the device type string from 'phone' to 'tablet'.",
) {
    compatibleWith("com.viber.voip")

    execute {
        var hookedCount = 0

        classes.forEach { classDef ->
            classDef.methods.forEach { method ->
                val mutableMethod = method as? MutableMethod ?: return@forEach
                val impl = mutableMethod.implementation as? MutableMethodImplementation ?: return@forEach
                val instructions = impl.instructions

                var tabletStringRef: StringReference? = null
                val phoneInsnIndices = mutableListOf<Int>()

                // Quét xem method này có chứa cả "tablet" và "phone" không
                instructions.forEachIndexed { index, insn ->
                    if (insn is ReferenceInstruction) {
                        val ref = insn.reference
                        if (ref is StringReference) {
                            if (ref.string == "tablet") {
                                tabletStringRef = ref
                            } else if (ref.string == "phone" && insn.opcode == Opcode.CONST_STRING) {
                                phoneInsnIndices.add(index)
                            }
                        }
                    }
                }

                // Nếu đúng là method xử lý phân loại thiết bị, tiến hành hoán đổi triệt để
                if (tabletStringRef != null && phoneInsnIndices.isNotEmpty()) {
                    phoneInsnIndices.forEach { idx ->
                        val oldInsn = instructions[idx] as ReferenceInstruction
                        val registerA = (oldInsn as OneRegisterInstruction).registerA
                        
                        // Thay thế lệnh gán "phone" thành "tablet"
                        instructions[idx] = ImmutableInstruction21c(
                            Opcode.CONST_STRING,
                            registerA,
                            tabletStringRef!!
                        )
                        hookedCount++
                    }
                }
            }
        }

        check(hookedCount > 0) {
            "Patch thất bại: Không tìm thấy phương thức chứa chuỗi định danh device type để ép kiểu!"
        }
    }
}
