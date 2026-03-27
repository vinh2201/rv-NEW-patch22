package app.revanced.patches.youtube.video.videoid

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.util.getting
import app.revanced.util.using
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val BytecodePatchContext.videoIdMethodMatch by getting {
    firstMethodComposite {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
        returnType("V")
        parameterTypes("L")
        instructions(
            allOf(Opcode.INVOKE_INTERFACE(), method { returnType == "Ljava/lang/String;" }),
            after(Opcode.MOVE_RESULT_OBJECT()), // videoId
            afterAtMost(
                6,
                method { toString() == "Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;" }),
            after(Opcode.RETURN_VOID())
        )
    }
} using {
    firstImmutableMethodDeclaratively {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
        returnType("[L")
        parameterTypes("L")
        instructions(
            524288L(),
        )
    }
}

internal val BytecodePatchContext.videoIdBackgroundPlayMethodMatch by composingFirstMethod {
    accessFlags(AccessFlags.DECLARED_SYNCHRONIZED, AccessFlags.FINAL, AccessFlags.PUBLIC)
    returnType("V")
    parameterTypes("L")
    instructions(
        method { returnType == "Ljava/lang/String;" },
        Opcode.MOVE_RESULT_OBJECT(),
        Opcode.IPUT_OBJECT(),
        Opcode.MONITOR_EXIT(),
        Opcode.RETURN_VOID(),
        Opcode.MONITOR_EXIT(),
        Opcode.RETURN_VOID(),
    )
    custom {
        immutableClassDef.methods.count() == 17 || // 20.39 and lower.
                immutableClassDef.methods.count() == 16 // 20.40+
    }
}
