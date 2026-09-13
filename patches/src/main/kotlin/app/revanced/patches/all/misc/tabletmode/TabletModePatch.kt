package app.revanced.patches.all.misc.tabletmode

// Đã sửa lại đường dẫn import đúng cho replaceInstruction
import app.revanced.patcher.extensions.MutableMethodExtensions.replaceInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.stringOption // Import delegate option của API 22
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import java.util.logging.Logger // Import Logger chuẩn

@Suppress("unused")
val tabletModePatch = bytecodePatch(
    name = "Tablet Mode",
    description = "Spoof a tablet smallest width so apps render their tablet UI."
    // Đã vứt biến 'options' ở đây đi!
) {
    // Đưa option vào trong block bằng delegation 'by' y hệt ApkJunkClean
    val smallestWidthOption by stringOption(
        default = "600",
        name = "Smallest width (dp)",
        description = "Reported smallestScreenWidthDp. 600 or higher unlocks most tablet layouts."
    )

    // Dùng 'execute' thay vì 'apply'
    execute {
        // Khởi tạo Logger thủ công
        val logger = Logger.getLogger(this::class.java.name)
        
        // Nhờ dùng 'by stringOption', biến smallestWidthOption giờ trả về String luôn, không cần .value nữa
        val width = (smallestWidthOption.toIntOrNull() ?: 600).coerceIn(320, 1200)
        var patched = 0

        classes.forEach { mutableClass ->
            mutableClass.methods.forEach { mutableMethod ->
                val instructions = mutableMethod.implementation?.instructions?.toList() ?: return@forEach

                for ((index, instruction) in instructions.withIndex()) {
                    if (instruction.opcode != Opcode.IGET) continue

                    val reference = (instruction as? ReferenceInstruction)?.reference as? FieldReference ?: continue

                    // Bắt trúng biến smallestScreenWidthDp
                    if (reference.definingClass == "Landroid/content/res/Configuration;" &&
                        reference.name == "smallestScreenWidthDp" &&
                        reference.type == "I"
                    ) {
                        val register = (instruction as? OneRegisterInstruction)?.registerA ?: continue
                        
                        // Fake số dp (đã convert sang hex)
                        mutableMethod.replaceInstruction(index, "const/16 v$register, 0x${width.toString(16)}")
                        patched++
                    }
                }
            }
        }
        
        // Log báo cáo ra CLI
        if (patched > 0) {
            logger.info("Spoofed smallest width to $width dp at $patched call site(s)")
        } else {
            logger.warning("No smallestScreenWidthDp reads found. No changes applied.")
        }
    }
}
