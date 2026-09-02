package app.revanced.patches.viber.misc

import app.revanced.patcher.Fingerprint
import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.fieldAccess
import app.revanced.patcher.literal
import app.revanced.patcher.methodCall
import app.revanced.patcher.opcode
import app.revanced.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import app.revanced.patcher.patch.bytecodePatch

object ResourcesFingerprint : Fingerprint(
    definingClass = "Landroid/content/res/Resources;",
    name = "getConfiguration",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "Landroid/content/res/Configuration;",
    parameters = emptyList(),
    filters = listOf(
        opcode(Opcode.INVOKE_VIRTUAL),
        methodCall(
            definingClass = "Landroid/content/res/Resources;",
            name = "getConfiguration"
        )
    )
)

val secondaryViberDevicePatch = bytecodePatch(
    name = "Secondary Viber Device",
    description = "Forces Viber to detect the device as a tablet, enabling the 'Link as secondary device' flow.",
) {
    execute {
        ResourcesFingerprint.method.addInstructions(
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