package app.revanced.patches.twitch.chat.haptics

import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType

internal val BytecodePatchContext.chatMessageHapticMethod by gettingFirstMethodDeclaratively("messageContextMenu") {
    returnType("V")
    parameterTypes("L", "Ltv/twitch/android/core/mvp/viewdelegate/EventDispatcher;")
}
