package app.revanced.patches.twitch.chat.antidelete

import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType

internal val BytecodePatchContext.messageHasBeenDeletedMethod by gettingFirstMethodDeclaratively {
    definingClass("/ModerationActionBundle;")
    name("getMessageHasBeenDeleted")
    returnType("Z")
}
