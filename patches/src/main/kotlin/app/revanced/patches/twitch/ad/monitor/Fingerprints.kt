package app.revanced.patches.twitch.ad.monitor

import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.definingClass
import app.revanced.patcher.returnType
import app.revanced.patcher.patch.BytecodePatchContext


internal val BytecodePatchContext.twitchApplicationOnCreateMethod by gettingFirstMethodDeclaratively {
    name("onCreate")
    definingClass("/TwitchApplication;")
    returnType("V")
}
