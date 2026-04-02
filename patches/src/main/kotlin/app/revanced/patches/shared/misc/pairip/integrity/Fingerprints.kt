package app.revanced.patches.shared.misc.pairip.integrity

import app.revanced.patcher.gettingFirstMethodDeclarativelyOrNull
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType

/**
 * Fingerprint for the Pairipcore signature check method.
 * It identifies the method by the error message thrown when the signature is invalid.
 */
internal val BytecodePatchContext.verifyIntegrityMethod by gettingFirstMethodDeclarativelyOrNull(
    "Apk signature is invalid.",
    "Signature check ok"
) {
    parameterTypes("Landroid/content/Context;")
    returnType("V")
}
