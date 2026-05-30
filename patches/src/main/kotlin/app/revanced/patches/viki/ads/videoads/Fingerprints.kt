package app.revanced.patches.viki.ads.videoads

import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.instructions
import app.revanced.patcher.method
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType

private const val MEDIA_RESOURCE_CLASS = "Lcom/viki/library/beans/MediaResource;"

internal val BytecodePatchContext.shouldLoadVideoAdsMethod by gettingFirstMethodDeclaratively("mediaResource") {
    returnType("Z")
    parameterTypes()
    instructions(
        method {
            returnType == "Z" &&
                parameterTypes.singleOrNull() == MEDIA_RESOURCE_CLASS
        }
    )
}