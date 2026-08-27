package app.revanced.patches.viber.ads

import app.revanced.patcher.definingClass
import app.revanced.patcher.firstMethodDeclarativelyOrNull
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import app.revanced.patcher.extensions.typeReference
import app.revanced.util.returnEarly
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = "Hide Ads",
    description = "Enables native Viber Plus main flag to remove ad containers.",
) {
    compatibleWith("com.viber.voip")

    apply {
        // Lấy class cờ (B.smali hoặc Llj/B;) trực tiếp từ lệnh NEW_INSTANCE
        val targetClass = findVPlusMainMatch.immutableMethod.implementation?.instructions
            ?.filterIsInstance<ReferenceInstruction>()
            ?.firstOrNull { it.opcode == Opcode.NEW_INSTANCE }
            ?.typeReference?.type ?: return@apply

        // Ép hàm boolean của class đó luôn trả về true
        firstMethodDeclarativelyOrNull {
            definingClass(targetClass)
            returnType("Z")
            parameterTypes()
        }?.returnEarly(true)
    }
}