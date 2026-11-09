package app.revanced.patches.viber.layout.typingbanner

import app.revanced.patcher.gettingFirstImmutableMethodOrNull
import app.revanced.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Any method of the repository that remembers the typing privacy banner.
 * Only present in Viber 29.x and newer.
 */
internal val BytecodePatchContext.typingPrivacyBannerAnchorMethod by gettingFirstImmutableMethodOrNull(
    "viber_plus_hide_is_typing_ftue_dismissed",
)

/**
 * The method of the repository that decides if the banner is shown:
 * it returns a boolean read from a `Boolean.booleanValue()` call.
 */
internal fun BytecodePatchContext.findTypingPrivacyBannerEligibilityMethod(): Method? {
    val anchor = typingPrivacyBannerAnchorMethod ?: return null
    val repositoryClass = classDefs.firstOrNull { it.type == anchor.definingClass } ?: return null

    return repositoryClass.methods.singleOrNull { method ->
        method.returnType == "Z" &&
            !AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.implementation?.instructions?.any { instruction ->
                if (instruction.opcode != Opcode.INVOKE_VIRTUAL) return@any false

                val reference = (instruction as ReferenceInstruction).reference as? MethodReference
                reference != null && reference.definingClass == "Ljava/lang/Boolean;" && reference.name == "booleanValue"
            } == true
    }
}
