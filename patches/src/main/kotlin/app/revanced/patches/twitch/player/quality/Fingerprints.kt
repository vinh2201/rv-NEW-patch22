package app.revanced.patches.twitch.player.quality

import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType

internal val BytecodePatchContext.setQualityMethod by gettingFirstMethodDeclaratively("auto") {
    returnType("V")
    parameterTypes("Lcom/amazonaws/ivs/player/MediaPlayer;", "Ljava/lang/String;")
}
