package app.revanced.patches.viber.misc

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation

@Suppress("unused")
val secondaryViberDevicePatch = bytecodePatch(
    name = "Secondary Viber Device",
    description = "Spoofs 200dpi globally in registration package to trigger tablet mode.",
) {
    compatibleWith("com.viber.voip")

    execute {
        var hookedCount = 0

        classes.forEach { classDef ->
            // Quét tất cả các lớp thuộc luồng Đăng ký / Registration
            if (!classDef.type.contains("registration", ignoreCase = true)) return@forEach

            classDef.methods.forEach { method ->
                // Bắt các phương thức khởi tạo hoặc vòng đời khả thi nhất
                if (method.name == "onCreate" || method.name == "attachBaseContext" || method.name == "<init>") {
                    val mutableMethod = method as? MutableMethod ?: return@forEach
                    val impl = mutableMethod.implementation as? MutableMethodImplementation ?: return@forEach
                    
                    mutableMethod.addInstructions(
                        0,
                        """
                        # Ép System Resources về 200 DPI (densityDpi = 200, density = 1.25f)
                        invoke-static {}, Landroid/content/res/Resources;->getSystem()Landroid/content/res/Resources;
                        move-result-object v0
                        if-eqz v0, :cond_dpi_sys_$hookedCount
                        invoke-virtual {v0}, Landroid/content/res/Resources;->getDisplayMetrics()Landroid/util/DisplayMetrics;
                        move-result-object v1
                        if-eqz v1, :cond_dpi_sys_$hookedCount
                        const/16 v0, 0xc8
                        iput v0, v1, Landroid/util/DisplayMetrics;->densityDpi:I
                        const v0, 0x3fa00000
                        iput v0, v1, Landroid/util/DisplayMetrics;->density:F
                        :cond_dpi_sys_$hookedCount
                        """.trimIndent()
                    )
                    hookedCount++
                }
            }
        }

        check(hookedCount > 0) {
            "Patch thất bại: Không tìm thấy phương thức nào trong package registration để tiêm mã DPI!"
        }
    }
}
