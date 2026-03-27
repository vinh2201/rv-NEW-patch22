package app.revanced.patches.viber.misc.navbar

import app.revanced.patcher.firstMethodDeclaratively
import app.revanced.patcher.firstImmutableMethodDeclaratively
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import app.revanced.util.getting
import app.revanced.util.using

internal val BytecodePatchContext.shouldShowTabIdMethod by getting {
    firstMethodDeclaratively {
        parameterTypes("I", "I")
        returnType("Z")
    }
} using { firstImmutableMethodDeclaratively("shouldShowTabId") }
