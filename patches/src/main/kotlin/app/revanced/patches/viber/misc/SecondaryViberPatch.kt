package app.revanced.patches.viber.misc

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation

@Suppress("unused")
val secondaryViberDevicePatch = bytecodePatch(
    name = "Secondary Viber Device",
    description = "Forces Viber RegistrationActivity to spoof tablet configuration.",
) {
    compatibleWith("com.viber.voip")

    execute {
        var hookedCount = 0

        classes.forEach { classDef ->
            // Khoanh vùng thẳng vào package chứa màn hình đăng ký/đăng nhập
            if (!classDef.type.contains("registration", ignoreCase = true) && 
                !classDef.type.contains("viber/voip/registration", ignoreCase = true)) {
                return@forEach
            }

            classDef.methods.forEach { method ->
                // Tìm hàm onCreate hoặc các hàm khởi tạo giao diện của Activity này
                if (method.name == "onCreate" || method.name.contains("init", ignoreCase = true)) {
                    val mutableMethod = method as? MutableMethod ?: return@forEach
                    val impl = mutableMethod.implementation as? MutableMethodImplementation ?: return@forEach
                    
                    // Tiêm code override Configuration ngay đầu hàm onCreate
                    // Ép smallestScreenWidthDp = 0x258 (600) và screenLayout thành Large/Tablet
                    mutableMethod.addInstructions(
                        0,
                        """
                        # Lấy Resources từ context hiện tại và ghi đè Configuration thành Tablet
                        invoke-virtual {p0}, Landroid/app/Activity;->getResources()Landroid/content/res/Resources;
                        move-result-object v0
                        if-eqz v0, :cond_viber_reg_skip
                        invoke-virtual {v0}, Landroid/content/res/Resources;->getConfiguration()Landroid/content/res/Configuration;
                        move-result-object v0
                        if-eqz v0, :cond_viber_reg_skip
                        const/16 v1, 0xc8
                        iput v1, v0, Landroid/content/res/Configuration;->smallestScreenWidthDp:I
                        iget v1, v0, Landroid/content/res/Configuration;->screenLayout:I
                        and-int/lit8 v1, v1, -0x10
                        or-int/lit8 v1, v1, 0x3
                        iput v1, v0, Landroid/content/res/Configuration;->screenLayout:I
                        :cond_viber_reg_skip
                        """.trimIndent()
                    )
                    hookedCount++
                }
            }
        }

        check(hookedCount >  0) {
            "Patch thất bại: Không tìm thấy RegistrationActivity để tiêm mã giả lập cấu hình!"
        }
    }
}
