package app.revanced.patches.twitch.ad.audio

import app.revanced.patcher.gettingFirstMethodDeclarativelyOrNull
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.returnType
import app.revanced.patcher.strings
import app.revanced.patcher.patch.BytecodePatchContext


internal val BytecodePatchContext.audioAdSdaMetadataMethod by gettingFirstMethodDeclarativelyOrNull {
    strings("X-TTV-AD-SDA-SEQUENCE-LENGTH")
}

internal val BytecodePatchContext.audioAdsPlayMethod by gettingFirstMethodDeclarativelyOrNull {
    parameterTypes("Ltv/twitch/android/shared/ads/models/AudioAdsPod;")
    returnType("V")
}