package app.revanced.patches.viki.ads.videoads

import app.revanced.patcher.anyInstruction
import app.revanced.patcher.gettingFirstMethod
import app.revanced.patcher.implementation
import app.revanced.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val MEDIA_RESOURCE_CLASS = "Lcom/viki/library/beans/MediaResource;"

internal val BytecodePatchContext.shouldLoadVideoAdsMethod by gettingFirstMethod("mediaResource") {
    returnType == "Z" &&
        parameterTypes.isEmpty() &&
        invokesBooleanMethodWith(MEDIA_RESOURCE_CLASS)
}

private fun Method.invokesBooleanMethodWith(parameterType: String) = implementation {
    anyInstruction {
        val reference = (this as? ReferenceInstruction)?.reference as? MethodReference
            ?: return@anyInstruction false

        reference.returnType == "Z" &&
            reference.parameterTypes.singleOrNull()?.toString() == parameterType
    }
}
