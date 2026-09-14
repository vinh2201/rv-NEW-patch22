package app.revanced.patches.all.misc.tabletmode

import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.intOption 
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod.Companion.toMutable 
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
    val smallestWidthOption by intOption(
        name = "Smallest width (dp)",
        default = 600,
        description = "Reported smallestScreenWidthDp. 600 or higher unlocks most tablet layouts."
    )

    execute {
        val logger = Logger.getLogger(this::class.java.name)
        
        // FIX LỖI TẠI ĐÂY: Dùng Elvis operator ?: 600 để ép kiểu Int? về Int không null
        val width = (smallestWidthOption ?: 600).coerceIn(320, 1200)
        
        var patched = 0

        classes.forEach { classDef ->
            classDef.methods.forEach { method ->
                val implementation = method.implementation ?: return@forEach
                val instructions = implementation.instructions.toList()

                for ((index, instruction) in instructions.withIndex()) {
                    if (instruction.opcode != Opcode.IGET) continue

                    val reference = (instruction as? ReferenceInstruction)?.reference as? FieldReference ?: continue

                    if (reference.definingClass == "Landroid/content/res/Configuration;" &&
                        reference.name == "smallestScreenWidthDp" &&
                        reference.type == "I"
                    ) {
                        val register = (instruction as? OneRegisterInstruction)?.registerA ?: continue
                        
                        method.toMutable().replaceInstruction(index, "const/16 v$register, 0x${width.toString(16)}")
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
