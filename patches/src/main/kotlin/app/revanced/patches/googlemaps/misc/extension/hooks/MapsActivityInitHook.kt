package app.revanced.patches.googlemaps.misc.extension.hooks

import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.returnType
import app.revanced.patcher.strings
import app.revanced.patches.shared.misc.extension.extensionHook

internal val mapsActivityInitHook = extensionHook {
    name("onCreate")
    returnType("V")
    parameterTypes("Landroid/os/Bundle;")
    strings("GmmActivity.onCreate")
}
