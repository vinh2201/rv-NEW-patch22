package app.revanced.patches.all.misc.tabletmode

import app.revanced.patcher.extensions.InstructionExtensions.replaceInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.options.StringOption
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

// Chuẩn API v22: Gộp chung key và title thành "name" duy nhất!
private val smallestWidthOption = StringOption(
    name = "Smallest width (dp)",
    default = "600",
    description = "Reported smallestScreenWidthDp. 600 or higher unlocks most tablet layouts."
)

@Suppress("unused")
val tabletModePatch = bytecodePatch(
    name = "Tablet Mode",
    description = "Spoof a tablet smallest width so apps render their tablet UI.",
    options = listOf(smallestWidthOption) 
) {
    apply {
        // Lấy value ra xài ngon ơ
        val width = (smallestWidthOption.value.toIntOrNull() ?: 600).coerceIn(320, 1200)
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
            patchLogger.info("Spoofed smallest width to $width dp at $patched call site(s)")
        } else {
            patchLogger.warning("No smallestScreenWidthDp reads found. No changes applied.")
        }
    }
}