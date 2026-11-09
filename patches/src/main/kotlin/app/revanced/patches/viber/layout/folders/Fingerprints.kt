package app.revanced.patches.viber.layout.folders

import app.revanced.patcher.gettingFirstImmutableMethodOrNull
import app.revanced.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/**
 * `toString()` of the "ChatFoldersFeaturePayload(maxFolders=N)" data class.
 * Present in Viber 23.x, 26.x and 29.x.
 */
internal val BytecodePatchContext.chatFoldersPayloadToStringMethod by gettingFirstImmutableMethodOrNull(
    "ChatFoldersFeaturePayload(maxFolders=",
)

/**
 * Getter of the `maxFolders` property of the payload: `public final a()I` (the name is obfuscated).
 * `hashCode()I` also returns an int read from the same field, so it is excluded.
 */
internal fun BytecodePatchContext.findMaxFoldersMethod(): Method? {
    val anchor = chatFoldersPayloadToStringMethod ?: return null
    val payloadClass = classDefs.firstOrNull { it.type == anchor.definingClass } ?: return null

    return payloadClass.methods.singleOrNull { method ->
        method.returnType == "I" &&
            method.parameterTypes.isEmpty() &&
            method.name != "hashCode" &&
            !AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.implementation?.instructions?.any { instruction ->
                instruction.opcode == Opcode.IGET &&
                    ((instruction as ReferenceInstruction).reference as? FieldReference)?.type == "I"
            } == true
    }
}
