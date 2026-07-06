package app.revanced.patches.twitch.ad.embedded

import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType

internal val BytecodePatchContext.rawManifestMethod by gettingFirstMethodDeclaratively {
    definingClass("Lz4m;")
    name("k")
    returnType("Ljava/lang/String;")
    parameterTypes()
}
