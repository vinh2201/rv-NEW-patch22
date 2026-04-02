package app.revanced.patches.shared.misc.pairip.logging

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext

internal val BytecodePatchContext.isDebuggingEnabledMethod by gettingFirstMethodDeclarativelyOrNull {
    name("isDebuggingEnabled")
    definingClass("Lcom/pairip/VMRunner;")
    returnType("Z")
}
