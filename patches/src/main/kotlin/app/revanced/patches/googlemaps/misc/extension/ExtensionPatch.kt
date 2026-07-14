package app.revanced.patches.googlemaps.misc.extension

import app.revanced.patches.googlemaps.misc.extension.hooks.mapsActivityInitHook
import app.revanced.patches.shared.misc.extension.sharedExtensionPatch

val extensionPatch = sharedExtensionPatch(mapsActivityInitHook)
