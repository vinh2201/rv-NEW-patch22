package app.revanced.patches.youtube.misc.litho.filter

import app.revanced.patcher.composingFirstMethod
import app.revanced.patcher.instructions
import app.revanced.patcher.invoke
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType

internal val BytecodePatchContext.lithoConverterBufferUpbFeatureFlagMethodMatch by composingFirstMethod {
    returnType("L")
    instructions(45419603L())
}
