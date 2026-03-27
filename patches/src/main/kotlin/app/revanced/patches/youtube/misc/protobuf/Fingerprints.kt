package app.revanced.patches.youtube.misc.protobuf

import app.revanced.patcher.accessFlags
import app.revanced.patcher.allOf
import app.revanced.patcher.custom
import app.revanced.patcher.firstMethodDeclaratively
import app.revanced.patcher.gettingFirstImmutableMethodDeclaratively
import app.revanced.patcher.instructions
import app.revanced.patcher.invoke
import app.revanced.patcher.method
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import app.revanced.patcher.type
import app.revanced.util.getting
import app.revanced.util.using
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val BytecodePatchContext.newElementProtobufParserMethod by getting {
    firstMethodDeclaratively {
        parameterTypes("L")
        returnType("[B")
        custom {
            // 'static' or 'public static'.
            AccessFlags.STATIC.isSet(accessFlags)
        }
        instructions(
            allOf(Opcode.CHECK_CAST(), type("[B")),
        )
    }
} using { protobufReflectionMethod }

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
