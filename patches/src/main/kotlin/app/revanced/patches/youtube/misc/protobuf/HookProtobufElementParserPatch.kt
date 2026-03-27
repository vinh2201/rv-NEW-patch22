package app.revanced.patches.youtube.misc.protobuf

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.classDef
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.youtube.misc.extension.sharedExtensionPatch
import app.revanced.util.cloneMutable

private lateinit var protobufElementParserMethod: MutableMethod

val hookProtobufElementParserPatch = bytecodePatch(
    description = "Hook to modify the Protobuf message class only accessible through reflection.",
) {
    dependsOn(sharedExtensionPatch)

    apply {
        protobufElementParserMethod = newElementProtobufParserMethod.apply {
            // Not enough registers in the method. Clone the method and use the
            // original method as an intermediate to call extension code.
            val helperMethod = cloneMutable(name = "patch_parseNewElement")
                .also(newElementProtobufParserMethod.classDef.methods::add)

            addInstructions(
                0,
                """
                    invoke-static { p0 }, $helperMethod
                    move-result-object p0
                    return-object p0
                """
            )
        }
    }
}

fun hookElement(
    methodDescriptor: String
) = protobufElementParserMethod.addInstructions(
    2,
    """
        invoke-static { p0 }, $methodDescriptor
        move-result-object p0
    """
)
