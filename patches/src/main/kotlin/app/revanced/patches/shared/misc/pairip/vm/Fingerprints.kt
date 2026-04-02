package app.revanced.patches.shared.misc.pairip.vm

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext

internal val BytecodePatchContext.launchVMMethod by gettingFirstMethodDeclarativelyOrNull {
    name("launch")
    definingClass("Lcom/pairip/StartupLauncher;")
    returnType("V")
}
