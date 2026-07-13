package app.revanced.patches.twitch.chat.autoclaim

import app.revanced.patcher.strings
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.returnType
import app.revanced.patcher.patch.BytecodePatchContext

internal val BytecodePatchContext.communityPointsButtonRenderMethod by gettingFirstMethodDeclaratively {
    returnType("V")
    strings("animation_explosion.json")
}
