@file:Suppress("SpellCheckingInspection")

package app.revanced.patches.youtube.misc.litho.filter

import app.revanced.patcher.extensions.addInstruction
import app.revanced.patches.shared.misc.litho.filter.EXTENSION_CLASS_DESCRIPTOR
import app.revanced.patches.shared.misc.litho.filter.lithoFilterPatch
import app.revanced.patches.shared.misc.litho.filter.protobufBufferReferenceMethod
import app.revanced.patches.youtube.misc.extension.sharedExtensionPatch
import app.revanced.patches.youtube.misc.playservice.is_20_22_or_greater
import app.revanced.patches.youtube.misc.playservice.versionCheckPatch
import app.revanced.util.insertLiteralOverride

val lithoFilterPatch = lithoFilterPatch(
    insertLegacyProtobufHook = {
        if (!is_20_22_or_greater) {
            // Non-native buffer.
            protobufBufferReferenceMethod.addInstruction(
                0,
                "invoke-static { p2 }, ${EXTENSION_CLASS_DESCRIPTOR}->setProtobufBuffer(Ljava/nio/ByteBuffer;)V",
            )
        }
    },
    getExtractIdentifierFromBuffer = { is_20_22_or_greater },
    executeBlock = {
        // region A/B test of new Litho native code.

        // Turn off a feature flag that enables native code of protobuf parsing (Upb protobuf).
        lithoConverterBufferUpbFeatureFlagMethodMatch.let {
            // 20.22 the flag is still enabled in one location, but what it does is not known.
            // Disable it anyway.
            it.method.insertLiteralOverride(
                it[0],
                false,
            )
        }
        // endregion
    }
) {
    dependsOn(sharedExtensionPatch, versionCheckPatch)
}
