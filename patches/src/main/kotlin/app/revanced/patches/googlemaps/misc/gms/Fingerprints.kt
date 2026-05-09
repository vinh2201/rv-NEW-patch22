package app.revanced.patches.googlemaps.misc.gms

import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType

val BytecodePatchContext.mapsActivityOnCreateMethod by gettingFirstMethodDeclaratively(
    "GmmActivity.onCreate",
) {
    name("onCreate")
    returnType("V")
    parameterTypes("Landroid/os/Bundle;")
}
