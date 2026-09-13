package app.revanced.patches.all.misc.tabletmode

// Đã fix: Import trực tiếp top-level function của API 22, bỏ cái MutableMethodExtensions đi
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.stringOption
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import java.util.logging.Logger

@Suppress("unused")
val tabletModePatch = bytecodePatch(
    name = "Tablet Mode",
    description = "Spoof a tablet smallest width so apps render their tablet UI."
) {
    val smallestWidthOption by stringOption(
        default = "600",
        name = "Smallest width (dp)",
        description = "Reported smallestScreenWidthDp. 600 or higher unlocks most tablet layouts."
    )

    execute {
        val logger = Logger.getLogger(this::class.java.name)
        
        // Đã fix: Thêm '?.' vì stringOption trả về String?
        val width = (smallestWidthOption?.toIntOrNull() ?: 600).coerceIn(320, 1200)
        var patched = 0

        classes.forEach { mutableClass ->
            mutableClass.methods.forEach { mutableMethod ->
                val instructions = mutableMethod.implementation?.instructions?.toList() ?: return@forEach

                for ((index, instruction) in instructions.withIndex()) {
                    if (instruction.opcode != Opcode.IGET) continue

                    val reference = (instruction as? ReferenceInstruction)?.reference as? FieldReference ?: continue

                    if (reference.definingClass == "Landroid/content/res/Configuration;" &&
                        reference.name == "smallestScreenWidthDp" &&
                        reference.type == "I"
                    ) {
                        val register = (instruction as? OneRegisterInstruction)?.registerA ?: continue
                        
                        // Nhờ import đúng ở trên, lệnh replaceInstruction giờ sẽ ăn 100%
                        mutableMethod.replaceInstruction(index, "const/16 v$register, 0x${width.toString(16)}")
                        patched++
                    }
                }
            }
        }
        
        if (patched > 0) {
            logger.info("Spoofed smallest width to $width dp at $patched call site(s)")
        } else {
            logger.warning("No smallestScreenWidthDp reads found. No changes applied.")
        }
    }
}
