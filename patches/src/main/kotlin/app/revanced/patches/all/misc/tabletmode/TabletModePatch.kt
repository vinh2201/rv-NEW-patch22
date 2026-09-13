package app.revanced.patches.all.misc.tabletmode

import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.stringOption
import app.revanced.patcher.util.proxy.mutableTypes.MutableClassDef
import app.revanced.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.util.MethodUtil
import java.util.logging.Logger

/**
 * Tìm kiếm [MutableMethod] chuẩn xác từ [MethodReference] trong [MutableClassDef].
 */
private fun MutableClassDef.findMutableMethodOf(method: MethodReference): MutableMethod = this.methods.first {
    MethodUtil.methodSignaturesMatch(it, method)
}

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
        val width = (smallestWidthOption?.toIntOrNull() ?: 600).coerceIn(320, 1200)
        var patched = 0

        classes.forEach { mutableClass ->
            var hasRef = false
            for (m in mutableClass.methods) {
                val impl = m.implementation ?: continue
                for (insn in impl.instructions) {
                    if (insn.opcode != Opcode.IGET) continue
                    val ref = (insn as? ReferenceInstruction)?.reference as? FieldReference ?: continue
                    if (ref.definingClass == "Landroid/content/res/Configuration;" &&
                        ref.name == "smallestScreenWidthDp" &&
                        ref.type == "I"
                    ) {
                        hasRef = true
                        break
                    }
                }
                if (hasRef) break
            }
            if (!hasRef) return@forEach

            for (method in mutableClass.methods) {
                val implementation = method.implementation ?: continue
                val instructions: List<Instruction> = implementation.instructions.toList()
                for ((index, instruction) in instructions.withIndex()) {
                    if (instruction.opcode != Opcode.IGET) continue
                    val reference = (instruction as? ReferenceInstruction)?.reference as? FieldReference ?: continue
                    if (reference.definingClass != "Landroid/content/res/Configuration;") continue
                    if (reference.name != "smallestScreenWidthDp") continue
                    if (reference.type != "I") continue

                    val register = (instruction as? OneRegisterInstruction)?.registerA ?: continue
                    
                    // Giờ đây dùng MutableClassDef khớp hoàn toàn với kiểu của ReVanced Patcher v22
                    val mutableMethod = mutableClass.findMutableMethodOf(method)
                    mutableMethod.replaceInstruction(index, "const/16 v$register, 0x${width.toString(16)}")
                    patched++
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
