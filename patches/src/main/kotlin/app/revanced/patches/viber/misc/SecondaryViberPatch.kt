package app.revanced.patches.viber.misc

import app.revanced.patcher.extensions.InstructionExtensions.replaceInstructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod

@Suppress("unused")
val secondaryViberDevicePatch = bytecodePatch(
    name = "Secondary Viber Device",
    description = "Forces ViewUtils.isRunningOnTablet to always return true.",
) {
    compatibleWith("com.viber.voip")

    execute {
        var hookedCount = 0

        classes.forEach { classDef ->
            // Tìm đúng class ViewUtils của Viber
            if (!classDef.type.endsWith("/ViewUtils;")) return@forEach

            classDef.methods.forEach { method ->
                // Tìm hàm isRunningOnTablet
                if (method.name == "isRunningOnTablet") {
                    val mutableMethod = method as? MutableMethod ?: return@forEach

                    // Đã thêm index 0 vào làm tham số đầu tiên
                    mutableMethod.replaceInstructions(
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
