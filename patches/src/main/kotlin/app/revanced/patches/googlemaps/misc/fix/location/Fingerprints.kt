package app.revanced.patches.googlemaps.misc.fix.location

import app.revanced.patcher.custom
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Matches the non-automotive location dialog display method.
 * This method checks GPS/WiFi/location state and shows custom dialogs
 * when location services appear disabled.
 * It receives a callback parameter that must be called with the result enum.
 *
 * Identified by "com.google.android.gsf.GOOGLE_LOCATION_SETTINGS" and
 * "android.settings.LOCATION_SOURCE_SETTINGS".
 */
internal val BytecodePatchContext.locationDialogMethod by gettingFirstMethodDeclaratively(
    "com.google.android.gsf.GOOGLE_LOCATION_SETTINGS",
    "android.settings.LOCATION_SOURCE_SETTINGS",
) {
    returnType("V")
}

/**
 * Matches the SettingsClient.checkLocationSettings() implementation method.
 * This is the concrete method that makes the IPC call to GmsCore's SettingsClient.
 * GmsCore returns RESOLUTION_REQUIRED (status 6) even when GPS is enabled, breaking
 * all 5 callers. By patching this single method to return an immediately-successful
 * Task, we fix all callers at once.
 *
 * Matched by parameter type LocationSettingsRequest (stable GMS API class) and
 * being a non-abstract public final method (the interface method is abstract).
 */
internal val BytecodePatchContext.checkLocationSettingsMethod by gettingFirstMethodDeclaratively {
    parameterTypes("Lcom/google/android/gms/location/LocationSettingsRequest;")
    custom {
        AccessFlags.FINAL.isSet(accessFlags) && !AccessFlags.ABSTRACT.isSet(accessFlags)
    }
}

/**
 * Matches the location state scanner method which checks "Use Location for Services"
 * via a content provider query to content://com.google.settings/partner. This authority gets
 * rewritten by the GmsCore patch, causing the query to fail and all location providers to be
 * marked as DISABLED_BY_SECURITY — blocking the entire location system.
 *
 * Identified by "Failed to get 'Use Location for Services' setting".
 */
internal val BytecodePatchContext.locationStateScannerMethod by gettingFirstMethodDeclaratively(
    "Failed to get 'Use Location for Services' setting",
) {
    returnType("V")
}

/**
 * Matches the FLP requestLocationUpdates method in the primary location listener class.
 * This private method calls FusedLocationProviderClient.requestLocationUpdates() which routes
 * through GmsCore. We patch it to use Android's LocationManager directly instead.
 *
 * Identified by "SecurityException: Maps App does not have location permission enabled."
 */
internal val BytecodePatchContext.flpRequestLocationUpdatesMethod by gettingFirstMethodDeclaratively(
    "SecurityException: Maps App does not have location permission enabled.",
) {
    returnType("V")
}

/**
 * Matches the FLP removeLocationUpdates method in the primary location listener class.
 * This method calls FusedLocationProviderClient.removeLocationUpdates() to stop receiving
 * location updates via GmsCore. We patch it to use our LocationManager helper instead.
 *
 * Identified by "stop() called when already stopped."
 */
internal val BytecodePatchContext.flpRemoveLocationUpdatesMethod by gettingFirstMethodDeclaratively(
    "stop() called when already stopped.",
) {
    returnType("V")
}
