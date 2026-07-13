package app.revanced.patches.twitch.misc.upsell

import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext

internal val BytecodePatchContext.turboUpsellOnCreateMethod by gettingFirstMethodDeclaratively {
    definingClass("/TurboAppStartUpsellDialogFragment;")
    name("onCreate")
    parameterTypes("Landroid/os/Bundle;")
}

internal val BytecodePatchContext.addEmailUpsellOnCreateMethod by gettingFirstMethodDeclaratively {
    definingClass("/AddEmailUpsellDialogFragment;")
    name("onCreate")
    parameterTypes("Landroid/os/Bundle;")
}
