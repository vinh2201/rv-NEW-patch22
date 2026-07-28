package app.revanced.patches.youtube.general.updatescreen

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.fingerprint
import app.revanced.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.util.MethodUtil

internal val appBlockingCheckResultToStringFingerprint = fingerprint {
    returns("Ljava/lang/String;")
    strings("AppBlockingCheckResult{intent=")
}

val disableUpdateScreen = bytecodePatch(
    name = "Disable update screen",
    description = "Disable the force update screen (\"Switch to YouTube.com\" or \"Update your app\").",
) {
    compatibleWith(
        "com.google.android.youtube"(
            "19.34.42",
            "20.07.39",
            "20.13.41",
            "20.14.43",
        )
    )

    execute {
        // Thay thế mutableClassOrThrow() bằng cách gọi classDefOrNull tiêu chuẩn
        val classDef = appBlockingCheckResultToStringFingerprint.classDefOrNull
            ?: throw PatchException("appBlockingCheckResultToStringFingerprint not found")

        // Tìm constructor với các tham số tương ứng (Dùng parameterTypes thay cho parameters)
        val targetMethod = classDef.methods.first { method ->
            MethodUtil.isConstructor(method) &&
                    method.parameterTypes.toList() == listOf("Landroid/content/Intent;", "Z")
        }

        // Chèn mã smali (Set p1 = 0x0 / false)
        targetMethod.addInstructions(
            1,
            "const/4 p1, 0x0"
        )
    }
}