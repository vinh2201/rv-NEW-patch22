package app.revanced.patches.viber.misc

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation

@Suppress("unused")
val secondaryViberDevicePatch = bytecodePatch(
    name = "Secondary Viber Device",
    description = "Spoofs 200dpi globally in Application class to trigger tablet mode.",
) {
    compatibleWith("com.viber.voip")

    execute {
        var hookedCount = 0

        classes.forEach { classDef ->
            // Đánh thẳng vào class Application khởi tạo đầu tiên của app (ViberApplication)
            if (classDef.superclass == "Landroid/app/Application;" || classDef.type.contains("ViberApplication", ignoreCase = true)) {
                classDef.methods.forEach { method ->
                    // Can thiệp ngay lúc app vừa bật lên, trước khi bất kỳ Activity nào kịp load
                    if (method.name == "onCreate") {
                        val mutableMethod = method as? MutableMethod ?: return@forEach
                        val impl = mutableMethod.implementation as? MutableMethodImplementation ?: return@forEach
                        
                        mutableMethod.addInstructions(
                            0,
                            """
                            # 1. Ép hệ thống (System Resources) về 200 DPI
                            invoke-static {}, Landroid/content/res/Resources;->getSystem()Landroid/content/res/Resources;
                            move-result-object v0
                            if-eqz v0, :cond_dpi_app
                            invoke-virtual {v0}, Landroid/content/res/Resources;->getDisplayMetrics()Landroid/util/DisplayMetrics;
                            move-result-object v1
                            if-eqz v1, :cond_dpi_app
                            const/16 v2, 0xc8
                            iput v2, v1, Landroid/util/DisplayMetrics;->densityDpi:I
                            # Float 1.25f (200/160) = 0x3fa00000 trong smali
                            const v2, 0x3fa00000
                            iput v2, v1, Landroid/util/DisplayMetrics;->density:F
                            
                            :cond_dpi_app
                            # 2. Ép App Context về 200 DPI và sw600dp
                            invoke-virtual {p0}, Landroid/app/Application;->getResources()Landroid/content/res/Resources;
                            move-result-object v0
                            if-eqz v0, :cond_dpi_done
                            invoke-virtual {v0}, Landroid/content/res/Resources;->getDisplayMetrics()Landroid/util/DisplayMetrics;
                            move-result-object v1
                            if-eqz v1, :cond_dpi_config
                            const/16 v2, 0xc8
                            iput v2, v1, Landroid/util/DisplayMetrics;->densityDpi:I
                            const v2, 0x3fa00000
                            iput v2, v1, Landroid/util/DisplayMetrics;->density:F
                            
                            :cond_dpi_config
                            invoke-virtual {v0}, Landroid/content/res/Resources;->getConfiguration()Landroid/content/res/Configuration;
                            move-result-object v0
                            if-eqz v0, :cond_dpi_done
                            const/16 v2, 0x258
                            iput v2, v0, Landroid/content/res/Configuration;->smallestScreenWidthDp:I
                            
                            :cond_dpi_done
                            """.trimIndent()
                        )
                        hookedCount++
                    }
                }
            }
        }

        check(hookedCount > 0) {
            "Patch thất bại: Không tìm thấy lớp Application để tiêm mã giả lập DPI!"
        }
    }
}
