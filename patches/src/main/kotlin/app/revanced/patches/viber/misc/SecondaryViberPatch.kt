package app.revanced.patches.viber.misc

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction

@Suppress("unused")
val forceTabletRegistrationPatch = bytecodePatch(
    name = "Secondary Viber Device",
    description = "Forces the registration payload to explicitly report 'tablet' device type, bypassing resource checks.",
) {
    compatibleWith("com.viber.voip")

    execute {
        var hookedCount = 0

        classes.forEach { classDef ->
            if (!classDef.type.contains("registration/U0")) return@forEach

            classDef.methods.forEach { method ->
                val mutableMethod = method as? MutableMethod ?: return@forEach
                val impl = mutableMethod.implementation as? MutableMethodImplementation ?: return@forEach
                val instructions = impl.instructions

                var i = 0
                while (i < instructions.size) {
                    val insn = instructions[i]

                    // Kiểm tra trực tiếp hằng số 0x7f050021 an toàn tuyệt đối qua NarrowLiteralInstruction
                    val narrowLiteral = (insn as? NarrowLiteralInstruction)?.narrowLiteral
                    if (narrowLiteral == 0x7f050021) {
                        if (i + 3 < instructions.size) {
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
