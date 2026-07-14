package app.revanced.patches.googlemaps.misc.gms

import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.Option
import app.revanced.patches.googlemaps.misc.extension.extensionPatch
import app.revanced.patches.googlemaps.misc.fix.location.fixLocationPatch
import app.revanced.patches.googlemaps.misc.fix.settingsmenu.restoreSettingsMenuPatch
import app.revanced.patches.googlemaps.misc.gms.Constants.MAPS_PACKAGE_NAME
import app.revanced.patches.googlemaps.misc.gms.Constants.REVANCED_MAPS_PACKAGE_NAME
import app.revanced.patches.shared.misc.gms.gmsCoreSupportPatch
import app.revanced.patches.shared.misc.gms.gmsCoreSupportResourcePatch

@Suppress("unused")
val gmsCoreSupportPatch = gmsCoreSupportPatch(
    fromPackageName = MAPS_PACKAGE_NAME,
    toPackageName = REVANCED_MAPS_PACKAGE_NAME,
    getMainActivityOnCreateMethodToGetInsertIndex =
        BytecodePatchContext::mapsActivityOnCreateMethod::get to { 0 },
    extensionPatch = extensionPatch,
    gmsCoreSupportResourcePatchFactory = ::gmsCoreSupportResourcePatch,
) {
    dependsOn(
        fixLocationPatch,
        restoreSettingsMenuPatch,
    )

    compatibleWith(
        MAPS_PACKAGE_NAME(
            "26.05.05",
            "26.07.05",
            "26.08.02",
            "26.09.00",
            "26.09.03",
            "26.09.06",
            "26.11.03",
        ),
    )
}

private fun gmsCoreSupportResourcePatch(
    gmsCoreVendorGroupIdOption: Option<String>,
) = gmsCoreSupportResourcePatch(
    fromPackageName = MAPS_PACKAGE_NAME,
    toPackageName = REVANCED_MAPS_PACKAGE_NAME,
    spoofedPackageSignature = "38918a453d07199354f8b19af05ec6562ced5788",
    gmsCoreVendorGroupIdOption = gmsCoreVendorGroupIdOption,
)
