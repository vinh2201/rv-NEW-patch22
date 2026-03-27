package app.revanced.patches.instagram.misc.signature

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.util.getting
import app.revanced.util.using

internal val BytecodePatchContext.isValidSignatureClassMethod by getting {
    firstMethodDeclaratively(
        "The provider for uri '",
        "' is not trusted: ",
    )
} using { isValidSignatureMethodMethod }

internal val BytecodePatchContext.isValidSignatureMethodMethod by gettingFirstMethodDeclaratively {
    parameterTypes("L", "Z")
    returnType("Z")
    instructions(method("keySet"))
}
