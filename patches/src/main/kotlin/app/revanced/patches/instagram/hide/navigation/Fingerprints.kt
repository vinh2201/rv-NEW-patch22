package app.revanced.patches.instagram.hide.navigation

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal val BytecodePatchContext.initializeNavigationButtonsListMethod by composingFirstMethod {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    parameterTypes("Lcom/instagram/common/session/UserSession;", "Z")
    returnType("Ljava/util/List;")
    custom {
        immutableClassDef.methods.any { m ->
            m.implementation?.instructions?.any { inst ->
                (inst as? ReferenceInstruction)?.reference.let {
                    it is StringReference && it.string == "default"
                }
            } == true
        }
    }
}

internal val BytecodePatchContext.navigationButtonsEnumMethod by gettingFirstImmutableMethodDeclaratively(
    "fragment_clips",
    "fragment_feed",
    "fragment_news",
    "fragment_search",
)
