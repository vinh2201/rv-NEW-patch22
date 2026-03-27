package app.revanced.patches.youtube.misc.engagement

import app.revanced.patcher.accessFlags
import app.revanced.patcher.allOf
import app.revanced.patcher.field
import app.revanced.patcher.firstMethodDeclaratively
import app.revanced.patcher.instructions
import app.revanced.patcher.invoke
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import app.revanced.patches.youtube.shared.getEngagementPanelControllerMethodMatch
import app.revanced.util.getting
import app.revanced.util.using
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val BytecodePatchContext.engagementPanelUpdateMethod by getting {
    firstMethodDeclaratively {
        accessFlags(AccessFlags.PRIVATE, AccessFlags.FINAL)
        returnType("V")
        parameterTypes("L", "Z")
        instructions(allOf(Opcode.IGET_OBJECT(), field { type == "Landroid/app/Activity;" }))
    }
} using { getEngagementPanelControllerMethodMatch().immutableMethod }
