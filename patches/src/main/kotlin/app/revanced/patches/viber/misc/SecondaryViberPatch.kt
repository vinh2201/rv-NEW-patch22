package app.revanced.patches.viber.misc

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import app.revanced.com.android.tools.smali.dexlib2.Opcode
import app.revanced.com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import app.revanced.com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import app.revanced.com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Suppress("unused")
val secondaryViberDevicePatch = bytecodePatch(
    name = "Secondary Viber Device",
    description = "Forces Viber to detect the device as a tablet, enabling the 'Link as secondary device' flow.",
) {
    compatibleWith("com.viber.voip")

    execute {
        // LỚP 1: Ép DeviceProperties.isTablet() luôn trả về true
        val devicePropertiesClassName = "Lcom/google/android/gms/common/util/DeviceProperties;"
        val devicePropertiesClass = classes.firstOrNull { it.type == devicePropertiesClassName }

        devicePropertiesClass?.methods?.filter {
            it.name == "isTablet" && it.returnType == "Z"
        }?.forEach { method ->
            val mutableMethod = method as? MutableMethod ?: return@forEach
            val impl = mutableMethod.implementation as? MutableMethodImplementation ?: return@forEach
            impl.instructions.clear()
            mutableMethod.addInstructions(
                0,
                """
                const/4 v0, 0x1
                return v0
                """.trimIndent()
            )
        }

        // LỚP 2: Quét toàn bộ mã nguồn Viber và tiêm logic Morphe đè Configuration sang Tablet
        var configHookCount = 0

        classes.forEach { classDef ->
            // Tối ưu tốc độ quét: Chỉ quét các class thuộc Viber hoặc Google Play Services
            if (!classDef.type.startsWith("Lcom/viber/") && !classDef.type.startsWith("Lcom/google/")) return@forEach

            classDef.methods.forEach { method ->
                val mutableMethod = method as? MutableMethod ?: return@forEach
                val impl = mutableMethod.implementation as? MutableMethodImplementation ?: return@forEach
                val instructions = impl.instructions

                var i = 0
                while (i < instructions.size) {
                    val insn = instructions[i]

                    // Tìm lệnh invoke-virtual gọi getConfiguration()
                    if (insn.opcode == Opcode.INVOKE_VIRTUAL || insn.opcode == Opcode.INVOKE_VIRTUAL_RANGE) {
                        val methodRef = (insn as? ReferenceInstruction)?.reference as? MethodReference
                        
                        if (methodRef?.definingClass == "Landroid/content/res/Resources;" &&
                            methodRef.name == "getConfiguration" &&
                            methodRef.returnType == "Landroid/content/res/Configuration;"
                        ) {
                            // Kiểm tra lệnh ngay sau đó có phải là move-result-object không
                            if (i + 1 < instructions.size) {
                                val nextInsn = instructions[i + 1]
                                if (nextInsn.opcode == Opcode.MOVE_RESULT_OBJECT) {
                                    val regInsn = nextInsn as? OneRegisterInstruction
                                    if (regInsn != null) {
                                        val targetReg = regInsn.registerNumber
                                        
                                        // Tiêm mã giả lập Tablet của Morphe vào ngay sau move-result-object
                                        mutableMethod.addInstructions(
                                            i + 2,
                                            """
                                            if-nez v$targetReg, :cond_viber_tablet_$configHookCount
                                            const/16 v0, 0x258
                                            iput v0, v$targetReg, Landroid/content/res/Configuration;->smallestScreenWidthDp:I
                                            iget v0, v$targetReg, Landroid/content/res/Configuration;->screenLayout:I
                                            and-int/lit8 v0, v0, -0x10
                                            or-int/lit8 v0, v0, 0x3
                                            iput v0, v$targetReg, Landroid/content/res/Configuration;->screenLayout:I
                                            :cond_viber_tablet_$configHookCount
                                            """.trimIndent()
                                        )
                                        configHookCount++
                                    }
                                }
                            }
                        }
                    }
                    i++
                }
            }
        }
    }
}
