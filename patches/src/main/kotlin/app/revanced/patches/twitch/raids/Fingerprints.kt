package app.revanced.patches.twitch.raids

import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType

internal val BytecodePatchContext.forceRaidNowSecondsMethod by gettingFirstMethodDeclaratively {
    definingClass("/RaidEventInfo;")
    name("getForceRaidNowSeconds")
    returnType("I")
}
