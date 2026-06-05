package app.revanced.patches.jsepol.trainstatuspt.contributors

import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import app.revanced.patches.jsepol.trainstatuspt.shared.TRAIN_DETAILS_ACTIVITY_CLASS

internal const val CONTRIBUIDOR_FIELD_NAME = "contribuidor"

internal val BytecodePatchContext.bloquearMenusMethod by gettingFirstMethodDeclaratively {
    definingClass(TRAIN_DETAILS_ACTIVITY_CLASS)
    name("bloquearMenus")
    returnType("V")
}
