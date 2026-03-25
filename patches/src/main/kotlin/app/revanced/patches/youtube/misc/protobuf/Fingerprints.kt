package app.revanced.patches.youtube.misc.protobuf

import app.revanced.patcher.ClassDefComposing
import app.revanced.patcher.accessFlags
import app.revanced.patcher.allOf
import app.revanced.patcher.gettingFirstImmutableMethodDeclaratively
import app.revanced.patcher.instructions
import app.revanced.patcher.invoke
import app.revanced.patcher.method
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import app.revanced.patcher.type
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef

/**
 * Matches using the method found in [protobufReflectionMethod].
 */
internal val ClassDef.newElementProtobufParserMethodMatch by ClassDefComposing.composingFirstMethod {
    accessFlags(AccessFlags.STATIC)
    parameterTypes("L")
    returnType("[B")
    instructions(
        allOf(Opcode.CHECK_CAST(), type("[B")),
    )
}

internal val BytecodePatchContext.protobufReflectionMethod by gettingFirstImmutableMethodDeclaratively {
    accessFlags(AccessFlags.PRIVATE, AccessFlags.STATIC)
    parameterTypes()
    returnType("Ljava/lang/reflect/Field;")
    instructions(
        "buf"(),
        allOf(Opcode.INVOKE_VIRTUAL(), method("getDeclaredField")),
        allOf(Opcode.INVOKE_VIRTUAL(), method("setAccessible")),
    )
}
