package app.revanced.patches.twitch.ad.video

import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.gettingFirstMethodDeclarativelyOrNull
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.returnType
import app.revanced.patcher.strings
import app.revanced.patcher.patch.BytecodePatchContext


internal val BytecodePatchContext.videoAdMetadataHandlerMethod by gettingFirstMethodDeclaratively {
    strings("twitch-stitched-ad", "twitch-maf-ad", "X-TTV-MAF-AD-RADS-TOKEN")
}

internal val BytecodePatchContext.midrollPubSubConsumerMethod by gettingFirstMethodDeclarativelyOrNull {
    parameterTypes("Ltv/twitch/android/models/ads/ChannelAdsPubSubEvent\$MidrollRequestType;")
    returnType("V")
}

internal val BytecodePatchContext.pbypPreflightConsumerMethod by gettingFirstMethodDeclarativelyOrNull {
    parameterTypes("Ltv/twitch/android/models/ads/PbypPreflightMessage;")
    returnType("V")
}