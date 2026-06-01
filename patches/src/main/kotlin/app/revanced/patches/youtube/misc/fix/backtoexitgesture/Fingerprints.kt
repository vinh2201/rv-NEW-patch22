package app.revanced.patches.youtube.misc.fix.backtoexitgesture

import app.revanced.patcher.accessFlags
import app.revanced.patcher.after
import app.revanced.patcher.allOf
import app.revanced.patcher.composingFirstMethod
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.instructions
import app.revanced.patcher.invoke
import app.revanced.patcher.method
import app.revanced.patcher.opcodes
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import app.revanced.patcher.type
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val BytecodePatchContext.scrollPositionMethodMatch by composingFirstMethod("scroll_position") {
    accessFlags(AccessFlags.PROTECTED, AccessFlags.FINAL)
    returnType("V")
    parameterTypes("L")
    opcodes(
        Opcode.IF_NEZ,
        Opcode.INVOKE_DIRECT,
        Opcode.RETURN_VOID,
    )
}

internal val BytecodePatchContext.recyclerViewTopScrollingMethodMatch by composingFirstMethod {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")
    parameterTypes()
    instructions(
        method { toString() == "Ljava/util/Iterator;->next()Ljava/lang/Object;" },
        after(Opcode.MOVE_RESULT_OBJECT()),
        after(allOf(Opcode.CHECK_CAST(), type("Landroid/support/v7/widget/RecyclerView;"))),
        after(0L()),
        after(method { definingClass == "Landroid/support/v7/widget/RecyclerView;" }),
        after(Opcode.GOTO()),
    )
}

internal val BytecodePatchContext.backToRefreshFeatureFlagMethodMatch by composingFirstMethod {
    returnType("Z")
    parameterTypes()
    instructions(45359221L())
}
