package app.revanced.patches.youtube.layout.player.fullscreen

import app.revanced.patcher.accessFlags
import app.revanced.patcher.afterAtMost
import app.revanced.patcher.composingFirstMethod
import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.instructions
import app.revanced.patcher.invoke
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * 19.46+
 */
internal val BytecodePatchContext.openVideosFullscreenPortraitMethodMatch by composingFirstMethod {
    returnType("V")
    parameterTypes("L", "Lj\$/util/Optional;")
    instructions(
        Opcode.MOVE_RESULT(), // Conditional check to modify.
        // Open videos fullscreen portrait feature flag.
        afterAtMost(5, 45666112L()), // Cannot be more than 5.
        afterAtMost(10, Opcode.MOVE_RESULT()),
    )
}

internal val BytecodePatchContext.openVideosFullscreenHookPatchExtensionMethod by gettingFirstMethodDeclaratively {
    name("isFullScreenPatchIncluded")
    definingClass(EXTENSION_CLASS_DESCRIPTOR)
    accessFlags(AccessFlags.PRIVATE, AccessFlags.STATIC)
    returnType("Z")
    parameterTypes()
}
