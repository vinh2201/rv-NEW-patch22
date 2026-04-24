package app.revanced.patches.twitch.ad.surestream

import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstMethodDeclarativelyOrNull
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
internal val BytecodePatchContext.sureStreamFireTrackingUrlWrapperMethod by gettingFirstMethodDeclarativelyOrNull {
    definingClass("tv/twitch/android/shared/ads/surestream/")
    returnType("Lio/reactivex/b;")
    parameterTypes("Ljava/lang/String;")
}
