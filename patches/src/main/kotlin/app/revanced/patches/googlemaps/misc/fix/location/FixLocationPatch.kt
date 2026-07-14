package app.revanced.patches.googlemaps.misc.fix.location

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.googlemaps.misc.gms.Constants.MAPS_PACKAGE_NAME
import app.revanced.util.indexOfFirstInstructionOrThrow
import app.revanced.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/shared/patches/GmsLocationHelper;"

/**
 * Fixes location functionality when using GmsCore instead of Google Play Services.
 *
 * The GmsCore patch breaks location through two independent mechanisms:
 *
 * 1. The location state scanner queries content://com.google.settings/partner for
 *    "use_location_for_services". The GmsCore patch rewrites this authority to
 *    content://app.revanced.android.settings/partner, which doesn't exist. On failure,
 *    ALL location providers are marked DISABLED_BY_SECURITY.
 *
 * 2. GmsCore's FusedLocationProvider doesn't work alongside real Play Services — it
 *    returns stale locations and checkLocationSettings() always returns RESOLUTION_REQUIRED.
 *
 * This patch fixes both by:
 * - Suppressing the location dialog that incorrectly shows when GPS works fine (Patch 1)
 * - Bypassing SettingsClient.checkLocationSettings() to return success (Patch 2)
 * - Skipping the content provider check that fails after authority rewriting (Patch 3)
 * - Replacing FusedLocationProviderClient with direct LocationManager calls (Patches 4-5)
 */
@Suppress("unused")
val fixLocationPatch = bytecodePatch(
    name = "Fix location",
    description = "Fixes location when using GmsCore by bypassing broken GMS location services " +
        "and using Android's LocationManager directly.",
) {
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

    execute {
        // Patch 1: Suppress non-automotive location dialog (amdf.c).
        // The method checks GPS/WiFi/location state and shows custom dialogs when location
        // services appear disabled. With GmsCore, the checks produce false negatives.
        // We inject at the start: call callback with NO_LOCATION_DEVICE, then return.
        // This tells Maps "location is fine" — the same as the normal success path.
        locationDialogMethod.apply {
            val lastInvokeInterfaceIndex = indexOfFirstInstructionReversedOrThrow(Opcode.INVOKE_INTERFACE)

            val callbackMethod = getInstruction(lastInvokeInterfaceIndex).methodReference!!

            val enumType = callbackMethod.parameterTypes[0]

            // NO_LOCATION_DEVICE is the second enum constant (the normal success path).
            val enumClass = classDefs.first { it.type == enumType }
            val noLocationDeviceField = enumClass.staticFields
                .filter { it.type == enumType }[1]
            val noLocationDeviceFieldRef = "$enumType->${noLocationDeviceField.name}:$enumType"

            addInstructions(
                0,
                """
                    sget-object p0, $noLocationDeviceFieldRef
                    invoke-interface { p4, p0 }, $callbackMethod
                    return-void
                """,
            )
        }

        // Patch 2: Bypass SettingsClient.checkLocationSettings().
        // GmsCore's SettingsClient always returns RESOLUTION_REQUIRED (status 6) even
        // when GPS is enabled. We patch the implementation to return an immediately-
        // completed Task with null result (success). All callers treat a successful Task
        // as "location settings are OK".
        checkLocationSettingsMethod.apply {
            val abstractTaskType = returnType

            val concreteTaskClass = classDefs.first { it.superclass == abstractTaskType }
            val concreteTaskType = concreteTaskClass.type

            val setResultMethod = concreteTaskClass.virtualMethods.first {
                it.returnType == "V" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == "Ljava/lang/Object;"
            }

            addInstructions(
                0,
                """
                    new-instance p1, $concreteTaskType
                    invoke-direct { p1 }, $concreteTaskType-><init>()V
                    const/4 v0, 0x0
                    invoke-virtual { p1, v0 }, $concreteTaskType->${setResultMethod.name}(Ljava/lang/Object;)V
                    return-object p1
                """,
            )
        }

        // Patch 3: Bypass "Use Location for Services" content provider check.
        // The location state scanner checks a boolean field: if true, query the content
        // provider; if false, skip to checking actual GPS/network status.
        // We force the field to false so the rewritten (broken) authority is never queried.
        locationStateScannerMethod.apply {
            val firstIgetBoolean = indexOfFirstInstructionOrThrow {
                opcode == Opcode.IGET_BOOLEAN
            }
            val fieldRef = getInstruction(firstIgetBoolean).fieldReference!!

            addInstructions(
                0,
                """
                    const/4 v0, 0x0
                    iput-boolean v0, p0, $fieldRef
                """,
            )
        }

        // Patch 4: Replace FLP requestLocationUpdates() with direct LocationManager.
        // GmsCore's FLP is unreliable alongside real Play Services. We register for ALL
        // available providers (GPS, network, fused, passive) via the extension helper.
        flpRequestLocationUpdatesMethod.apply {
            addInstructions(
                0,
                """
                    invoke-static {}, Landroid/os/Looper;->myLooper()Landroid/os/Looper;
                    move-result-object v0
                    invoke-static { p0, v0 }, ${EXTENSION_CLASS_DESCRIPTOR}->requestLocationUpdates(Ljava/lang/Object;Landroid/os/Looper;)V
                    return-void
                """,
            )
        }

        // Patch 5: Inject removeLocationUpdates at the start of the stop method.
        // The original code still runs after (sets j=false, tries FLP removal which
        // fails harmlessly in the existing try-catch).
        flpRemoveLocationUpdatesMethod.apply {
            addInstructions(
                0,
                "invoke-static {}, ${EXTENSION_CLASS_DESCRIPTOR}->removeLocationUpdates()V",
            )
        }
    }
}
