package app.revanced.patches.viber.misc

import app.revanced.patcher.patch.bytecodePatch
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

@Suppress("unused")
val forceTabletRegistrationPatch = bytecodePatch(
    name = "Secondary Viber Device",
    description = "Radar patch để soi xem Viber nó giấu chữ 'phone' ở đâu.",
) {
    compatibleWith("com.viber.voip")

    execute {
        var foundMethod = false

        classes.forEach { classDef ->
            if (!classDef.type.contains("viber/voip/registration")) return@forEach

            classDef.methods.forEach { method ->
                val mutableMethod = method as? MutableMethod ?: return@forEach
                val impl = mutableMethod.implementation as? MutableMethodImplementation ?: return@forEach
                val instructions = impl.instructions

                var isPayloadBuilder = false
                for (insn in instructions) {
                    if (insn is ReferenceInstruction && insn.reference is MethodReference) {
                        val ref = insn.reference as MethodReference
                        if (ref.name == "getDeviceManufacturer" || ref.name == "getSystemVersion") {
                            isPayloadBuilder = true
                            break
                        }
                    }
                }

                if (isPayloadBuilder) {
                    foundMethod = true
                    
                    // BẮT ĐẦU QUÉT RADAR VÀ IN RA CONSOLE
                    println("\n========== [RADAR DETECTED] ==========")
                    println("Class tìm thấy: ${classDef.type}")
                    println("Method tìm thấy: ${method.name}")
                    println("--- DANH SÁCH CÁC CHUỖI CÓ TRONG METHOD NÀY ---")
                    
                    for (insn in instructions) {
                        if (insn is ReferenceInstruction && insn.reference is StringReference) {
                            val str = (insn.reference as StringReference).string
                            println(" -> \"$str\"")
                        }
                    }
                    println("========================================\n")
                }
            }
        }

        check(foundMethod) {
            "Radar hỏng: Không tìm thấy hàm chứa getDeviceManufacturer!"
        }
        
        // Cố tình văng lỗi để dừng tiến trình Patch, 
        // mục đích của chúng ta ở bước này chỉ là xem Log trên màn hình!
        check(false) {
            "Dừng ở đây thôi! Bác hãy cuộn lên phía trên trong cửa sổ CMD/Terminal xem Radar nó in ra danh sách chữ gì rồi paste cho em xem nhé!"
        }
    }
}
