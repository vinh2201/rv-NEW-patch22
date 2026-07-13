package app.revanced.patches.twitch.misc.hide

import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType

internal val BytecodePatchContext.baseViewDelegateConstructorMethod by gettingFirstMethodDeclaratively("context", "contentView") {
    definingClass("/BaseViewDelegate;")
    returnType("V")
}