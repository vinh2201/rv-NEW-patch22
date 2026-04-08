package app.revanced.patches.youtube.misc.gms

import app.revanced.patcher.accessFlags
import app.revanced.patcher.after
import app.revanced.patcher.firstMethodComposite
import app.revanced.patcher.instructions
import app.revanced.patcher.invoke
import app.revanced.patcher.method
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import app.revanced.patches.shared.misc.mapping.ResourceType
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal fun BytecodePatchContext.getSpecificNetworkErrorViewControllerMethodMatch(
    hasContentBooleanParameter: Boolean
) = firstMethodComposite {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")

    if (hasContentBooleanParameter) parameterTypes("Z")
    else parameterTypes()

    instructions(
        ResourceType.DRAWABLE("ic_offline_no_content_upside_down"),
        ResourceType.STRING("offline_no_content_body_text_not_offline_eligible"),
        method { name == "getString" && returnType == "Ljava/lang/String;" },
        after(Opcode.MOVE_RESULT_OBJECT()),
    )
}
