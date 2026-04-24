package app.revanced.patches.twitch.ad.pubsub

import app.revanced.patcher.gettingFirstMethodDeclarativelyOrNull
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import app.revanced.patcher.strings


internal val BytecodePatchContext.pubSubChannelAdsDispatcherInvokeMethod by gettingFirstMethodDeclarativelyOrNull {
    name("invoke")
    returnType("Ljava/lang/Object;")
    parameterTypes("Ljava/lang/Object;")
    strings("ChannelAdsPubSubEvent.MidrollRequestType")
}

internal val BytecodePatchContext.midrollRequestTypeClassMethod by gettingFirstMethodDeclarativelyOrNull {
    strings("MidrollRequestType(pbypPreflightMessage=")
}
