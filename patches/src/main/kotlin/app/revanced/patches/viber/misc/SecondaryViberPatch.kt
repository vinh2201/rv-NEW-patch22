package app.revanced.patches.viber.misc

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation

@Suppress("unused")
val secondaryViberDevicePatch = bytecodePatch(
    name = "Secondary Viber Device",
    description = "Forces ViewUtils.isRunningOnTablet to always return true.",
) {
    compatibleWith("com.viber.voip")

    execute {
        var hookedCount = 0

        classes.forEach { classDef ->
            // Mở rộng bộ lọc sang mọi class chứa ViewUtils để tránh lệch package path
            if (!classDef.type.contains("ViewUtils")) return@forEach

            classDef.methods.forEach { method ->
                if (method.name == "isRunningOnTablet") {
                    val mutableMethod = method as? MutableMethod ?: return@forEach
                    val impl = mutableMethod.implementation as? MutableMethodImplementation ?: return@forEach

                    // Xóa sạch logic đo đạc cũ, ép trả về true tuyệt đối
                    impl.instructions.clear()
                    mutableMethod.addInstructions(
                        0,
                        """
                        const/4 v0, 0x1
                        return v0
                        """.trimIndent()
                    )
                    hookedCount++
                }
            }
        }

        check(hookedCount > 0) {
            "Patch thất bại: Không tìm thấy phương thức isRunningOnTablet trong ViewUtils!"
        }
    }
}
