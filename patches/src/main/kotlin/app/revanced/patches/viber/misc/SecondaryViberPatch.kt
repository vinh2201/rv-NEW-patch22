package app.revanced.patches.viber.misc

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.fingerprint.method.impl.BytecodeFingerprint.Companion.bytecodeFingerprint
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private val resourcesFingerprint = bytecodeFingerprint(
    returnType = "Landroid/content/res/Configuration;",
    parameters = emptyList(),
    opcodes = listOf(Opcode.INVOKE_VIRTUAL),
) { method, _ ->
    method.implementation?.instructions?.any { instruction ->
        if (instruction.opcode != Opcode.INVOKE_VIRTUAL) return@any false
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@any false
        reference.definingClass == "Landroid/content/res/Resources;" && reference.name == "getConfiguration"
    } ?: false
}

@Suppress("unused")
val secondaryViberDevicePatch = bytecodePatch(
    name = "Secondary Viber Device",
    description = "Forces Viber to detect the device as a tablet, enabling the 'Link as secondary device' flow.",
) {
    fingerprints(resourcesFingerprint)

    execute {
        val method = resourcesFingerprint.result?.mutableMethod
            ?: error("ResourcesFingerprint not found in target APK")

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