package app.revanced.patches.viber.misc

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation

@Suppress("unused")
val secondaryViberDevicePatch = bytecodePatch(
    name = "Secondary Viber Device",
    description = "Forces Viber RegistrationActivity to apply tablet override configuration.",
) {
    compatibleWith("com.viber.voip")

    execute {
        var hookedCount = 0

        classes.forEach { classDef ->
            if (!classDef.type.contains("registration", ignoreCase = true) && 
                !classDef.type.contains("viber/voip/registration", ignoreCase = true)) {
                return@forEach
            }

            classDef.methods.forEach { method ->
                // Tìm hàm attachBaseContext hoặc onCreate để can thiệp sớm nhất có thể
                if (method.name == "attachBaseContext" || method.name == "onCreate") {
                    val mutableMethod = method as? MutableMethod ?: return@forEach
                    val impl = mutableMethod.implementation as? MutableMethodImplementation ?: return@forEach
                    
                    // Tiêm mã tạo Configuration giả lập Tablet và gọi applyOverrideConfiguration
                    mutableMethod.addInstructions(
                        0,
                        """
                        new-instance v0, Landroid/content/res/Configuration;
                        invoke-direct {v0}, Landroid/content/res/Configuration;-><init>()V
                        const/16 v1, 0xc8
                        iput v1, v0, Landroid/content/res/Configuration;->smallestScreenWidthDp:I
                        iget v1, v0, Landroid/content/res/Configuration;->screenLayout:I
                        and-int/lit8 v1, v1, -0x10
                        or-int/lit8 v1, v1, 0x3
                        iput v1, v0, Landroid/content/res/Configuration;->screenLayout:I
                        invoke-virtual {p0, v0}, ${classDef.type}->applyOverrideConfiguration(Landroid/content/res/Configuration;)V
                        """.trimIndent()
                    )
                    hookedCount++
                }
            }
        }

        check(hookedCount > 0) {
            "Patch thất bại: Không tìm thấy phương thức khởi tạo của RegistrationActivity để override configuration!"
        }
    }
}
