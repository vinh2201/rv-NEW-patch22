package app.revanced.patches.strava.groupkudos

import app.revanced.patcher.firstMethod
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.util.getting
import app.revanced.util.using

internal val BytecodePatchContext.initMethod by gettingFirstMethodDeclaratively {
    name("<init>")
    parameterTypes("Lcom/strava/feed/view/modal/GroupTabFragment;", "Z", "Landroidx/fragment/app/FragmentManager;")
}

internal val BytecodePatchContext.actionHandlerMethod by getting {
    firstMethod("state")
} using { initMethod }
