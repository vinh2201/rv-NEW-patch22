package app.revanced.patches.viber.misc

import app.revanced.patcher.patch.bytecodePatch
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Suppress("unused")
val forceTabletRegistrationPatch = bytecodePatch(
    name = "Secondary Viber Device",
    description = "Radar V2: Truy vết tận gốc resource 0x7f050021",
) {
    compatibleWith("com.viber.voip")

    execute {
        var foundRes = false

        classes.forEach { classDef ->
            // BỘ LỌC THẦN THÁNH: Bỏ qua toàn bộ các class R (R.java) chứa định nghĩa ID tĩnh
            val className = classDef.type.substringAfterLast("/")
            if (className.startsWith("R$") || className == "R;") return@forEach

            classDef.methods.forEach { method ->
                val mutableMethod = method as? MutableMethod ?: return@forEach
                val impl = mutableMethod.implementation as? MutableMethodImplementation ?: return@forEach
                val instructions = impl.instructions

                for (i in instructions.indices) {
                    val insn = instructions[i]
                    
                    // Bắt đúng ID 0x7f050021
                    if (insn is NarrowLiteralInstruction && insn.narrowLiteral == 0x7f050021) {
                        foundRes = true
                        println("\n========== [RADAR V2: TARGET ACQUIRED] ==========")
                        println("Class: ${classDef.type}")
                        println("Method: ${method.name}")
                        println("--- 10 LỆNH NGAY SAU KHI NẠP RESOURCE ID ---")
                        
                        val limit = minOf(i + 10, instructions.size)
                        for (j in i until limit) {
                            val nextInsn = instructions[j]
                            val opcodeName = nextInsn.opcode.name
                            
                            // Trích xuất tên hàm nếu nó gọi invoke-
                            var extraInfo = ""
                            if (nextInsn is ReferenceInstruction && nextInsn.reference is MethodReference) {
                                val ref = nextInsn.reference as MethodReference
                                extraInfo = " -> ${ref.definingClass}->${ref.name}()"
                            }
                            
                            println("[$j] $opcodeName $extraInfo")
                        }
                        println("=================================================\n")
                    }
                }
            }
        }

        check(foundRes) {
            "Radar V2 hỏng: Không tìm thấy bất kỳ chỗ nào xài ID 0x7f050021 ngoài file R! Có thể nó đổi ID rồi."
        }
        
        // Cố tình crash để dừng Patch và xem log
        check(false) {
            "Dừng! Bác cuộn lên xem thằng Viber nó gọi hàm gì với cái ID 0x7f050021 này nhé! =))"
        }
    }
}
