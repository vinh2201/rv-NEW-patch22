package app.revanced.patches.viber.ads

import app.revanced.patcher.definingClass
import app.revanced.patcher.firstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType

internal val BytecodePatchContext.isAdsFreeMethodMatch
    get() = firstMethodDeclaratively {
        definingClass("Lcom/viber/voip/feature/viberplus/presentation/settings/ViberPlusSettingsState;")
        name("isAdsFree")
        returnType("Z")
        parameterTypes()
    }