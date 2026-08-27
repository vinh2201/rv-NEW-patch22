package app.revanced.patches.viber.ads

import app.revanced.patcher.definingClass
import app.revanced.patcher.firstMethodDeclarativelyOrNull
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import app.revanced.util.returnEarly
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = "Hide Ads",
    description = "Enables native Viber Plus main flag safely across versions.",
) {
    compatibleWith("com.viber.voip")

    apply {
        val method = findVPlusMainMatch.immutableMethod
        val instructions = method.implementation?.instructions?.toList() ?: return@apply

        // 1. Tìm chính xác vị trí dòng chứa chuỗi định danh trong bytecode
        val stringIndex = instructions.indexOfFirst {
            it is ReferenceInstruction && 
            it.reference is StringReference && 
            (it.reference as StringReference).string == "viber_plus_debug_ads_free_flag"
        }
        if (stringIndex == -1) return@apply

        // 2. Đi ngược lên trên từ vị trí chuỗi đó để tóm đúng lệnh new-instance đi kèm
        var targetClass: String? = null
        for (i in stringIndex downTo 0) {
            val instr = instructions[i]
            if (instr.opcode == Opcode.NEW_INSTANCE) {
                targetClass = (instr as ReferenceInstruction).typeReference.type
                break
            }
        }
        if (targetClass == null) return@apply

        // 3. Ép hàm trả về true an toàn
        firstMethodDeclarativelyOrNull {
            definingClass(targetClass)
            returnType("Z")
            parameterTypes()
        }?.returnEarly(true)
    }
}