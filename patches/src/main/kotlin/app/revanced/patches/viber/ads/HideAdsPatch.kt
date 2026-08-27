package app.revanced.patches.viber.ads

import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.typeReference
import app.revanced.patcher.firstMethodDeclaratively
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import app.revanced.util.returnEarly
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = "Remove Ads",
    description = "Disables ads in Viber.",
) {
    // Chỉnh sửa lại version tương thích tùy theo project của bạn
    compatibleWith("com.viber.voip")

    apply {
        // Gọi fingerprint đã tạo ở trên và ép nó luôn trả về true (1)
        isAdsFreeMethodMatch.returnEarly(1)
    }
}
