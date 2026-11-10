package app.revanced.patches.viber.misc.secondary

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.shared.misc.mapping.ResourceType
import app.revanced.patches.shared.misc.mapping.resourceMappingPatch
import java.util.logging.Logger

@Suppress("unused")
val linkAsSecondaryDevicePatch = bytecodePatch(
    name = "Link as secondary device",
    description = "(PORTED) Links to your main Viber phone. Works with Viber 23.x, 26.x and 29.x.",
) {
    compatibleWith("com.viber.voip")

    dependsOn(resourceMappingPatch)

    apply {
        val logger = Logger.getLogger(this::class.java.name)

        // The device always starts in secondary mode, as if it was a tablet.
        val secondaryDefault = findSecondaryModeDefault()
            ?: throw PatchException("Could not find the secondary mode default. This Viber version is not supported.")

        firstMethod(secondaryDefault.result.method).addInstructions(
            secondaryDefault.result.moveResultIndex + 1,
            "const/16 v${secondaryDefault.result.register}, 0x1",
        )

        // ... and it stays in secondary mode after the registration is reset.
        val resets = findSecondaryModeResets(secondaryDefault.tabletCheck)
        if (resets.isEmpty()) logger.warning("Could not find the secondary mode resets.")

        resets.groupBy { it.method }.forEach { (method, results) ->
            val mutableMethod = firstMethod(method)

            results.sortedByDescending { it.moveResultIndex }.forEach { reset ->
                mutableMethod.addInstructions(reset.moveResultIndex + 1, "const/16 v${reset.register}, 0x1")
            }
        }

        // The device type sent on registration is "tablet" when the device is in secondary mode.
        val secondaryModeGetter = findSecondaryModeGetter()
        val resourceId = runCatching { ResourceType.BOOL["send_tablet_device_type_on_registration"] }.getOrNull()
        val registration = if (secondaryModeGetter != null && resourceId != null) {
            findRegistrationDeviceType(resourceId)
        } else {
            null
        }

        if (registration == null) {
            logger.warning("Could not find the registration device type. The device type is not changed.")
            return@apply
        }

        val method = firstMethod(registration.method)
        method.addInstructionsWithLabels(
            registration.moveResultIndex + 1,
            """
                if-nez v${registration.register}, :registration_device_type_resolved
                invoke-static { }, ${secondaryModeGetter!!.definingClass}->${secondaryModeGetter.name}()Z
                move-result v${registration.register}
            """,
            ExternalLabel("registration_device_type_resolved", method.getInstruction(registration.moveResultIndex + 1)),
        )
    }
}
