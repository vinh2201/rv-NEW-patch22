package app.revanced.patches.viber.misc

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference


@Suppress("unused")
val secondaryViberDevicePatch = bytecodePatch(
    name = "Secondary Viber Device",
    description = "Forces Viber to detect the device as a tablet, enabling the 'Link as secondary device' flow.",
) {
    execute {
        // Quét trực tiếp trong classes để tìm hàm gọi Resources.getConfiguration()
        val method = classes.flatMap { it.methods }.firstOrNull { method ->
            if (method.returnType != "Landroid/content/res/Configuration;") return@firstOrNull false
            val instructions = method.implementation?.instructions ?: return@firstOrNull false
            instructions.any { instruction ->
                if (instruction.opcode != Opcode.INVOKE_VIRTUAL) return@any false
                val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@any false
                ref.definingClass == "Landroid/content/res/Resources;" && ref.name == "getConfiguration"
            }
        } ?: error("Resources.getConfiguration target method not found in Viber APK")

        method.addInstructions(
            2,
            """
            const/16 v1, 0x258
            iput v1, v0, Landroid/content/res/Configuration;->smallestScreenWidthDp:I
            
            const/4 v1, 0x0f
            iget v2, v0, Landroid/content/res/Configuration;->screenLayout:I
            and-int/2addr v2, v1
            if-gez v2, :cond_viber_tablet_0
            not-int v1, v1
            and-int/2addr v0, v1
            const/4 v1, 0x02
            or-int/2addr v0, v1
            :cond_viber_tablet_0
            """
        )
    }
}