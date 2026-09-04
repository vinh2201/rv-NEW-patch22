package app.revanced.patches.viber.misc

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation

@Suppress("unused")
val secondaryViberDevicePatch = bytecodePatch(
    name = "Secondary Viber Device",
    description = "Spoofs 200dpi globally in RegistrationActivity to trigger tablet mode.",
) {
    compatibleWith("com.viber.voip")

    execute {
        var hookedCount = 0

        classes.forEach { classDef ->
            // Đánh thẳng vào RegistrationActivity mà bác đã tìm ra qua lệnh ADB
            if (!classDef.type.contains("RegistrationActivity", ignoreCase = true)) return@forEach

            classDef.methods.forEach { method ->
                // Tiêm vào đầu hàm onCreate
                if (method.name == "onCreate") {
                    val mutableMethod = method as? MutableMethod ?: return@forEach
                    val impl = mutableMethod.implementation as? MutableMethodImplementation ?: return@forEach
                    
                    mutableMethod.addInstructions(
                        0,
                        """
                        # 1. Ép hệ thống (System Resources) về 200 DPI
                        invoke-static {}, Landroid/content/res/Resources;->getSystem()Landroid/content/res/Resources;
                        move-result-object v0
                        if-eqz v0, :cond_dpi_sys
                        invoke-virtual {v0}, Landroid/content/res/Resources;->getDisplayMetrics()Landroid/util/DisplayMetrics;
                        move-result-object v1
                        if-eqz v1, :cond_dpi_sys
                        const/16 v2, 0xc8
                        iput v2, v1, Landroid/util/DisplayMetrics;->densityDpi:I
                        const v2, 0x3fa00000
                        iput v2, v1, Landroid/util/DisplayMetrics;->density:F
                        
                        :cond_dpi_sys
                        # 2. Ép App Context về 200 DPI và sw600dp
                        invoke-virtual {p0}, Landroid/app/Activity;->getResources()Landroid/content/res/Resources;
                        move-result-object v0
                        if-eqz v0, :cond_dpi_done
                        invoke-virtual {v0}, Landroid/content/res/Resources;->getDisplayMetrics()Landroid/util/DisplayMetrics;
                        move-result-object v1
                        if-eqz v1, :cond_dpi_conf
                        const/16 v2, 0xc8
                        iput v2, v1, Landroid/util/DisplayMetrics;->densityDpi:I
                        const v2, 0x3fa00000
                        iput v2, v1, Landroid/util/DisplayMetrics;->density:F
                        
                        :cond_dpi_conf
                        invoke-virtual {v0}, Landroid/content/res/Resources;->getConfiguration()Landroid/content/res/Configuration;
                        move-result-object v0
                        if-eqz v0, :cond_dpi_done
                        const/16 v2, 0x258
                        iput v2, v0, Landroid/content/res/Configuration;->smallestScreenWidthDp:I
                        iget v2, v0, Landroid/content/res/Configuration;->screenLayout:I
                        and-int/lit8 v2, v2, -0x10
                        or-int/lit8 v2, v2, 0x3
                        iput v2, v0, Landroid/content/res/Configuration;->screenLayout:I
                        
                        :cond_dpi_done
                        """.trimIndent()
                    )
                    hookedCount++
                }
            }
        }

        check(hookedCount > 0) {
            "Patch thất bại: Không tìm thấy RegistrationActivity để tiêm mã giả lập DPI!"
        }
    }
}
