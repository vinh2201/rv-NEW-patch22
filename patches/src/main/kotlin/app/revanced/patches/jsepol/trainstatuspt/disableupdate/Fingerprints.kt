package app.revanced.patches.jsepol.trainstatuspt.disableupdate

import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import app.revanced.patches.jsepol.trainstatuspt.shared.MAIN_ACTIVITY_CLASS

internal val BytecodePatchContext.showNewVersionDialogMethod by gettingFirstMethodDeclaratively {
    definingClass(MAIN_ACTIVITY_CLASS)
    name("showNewVersionDialog")
    returnType("V")
}
