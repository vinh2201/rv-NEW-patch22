package app.revanced.patches.zwanoo.speedtest.misc

import app.revanced.patcher.accessFlags
import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.AccessFlags

internal val BytecodePatchContext.subscriptionExpiryMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/ookla/speedtest/useraccounts/W;")
    name("h")
    returnType("Ljava/lang/String;")
    accessFlags(AccessFlags.PUBLIC)
}

internal val BytecodePatchContext.showUpgradeDialogMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/ookla/speedtest/app/userprompt/a;")
    name("a")
    returnType("V")
    accessFlags(AccessFlags.PUBLIC)
}
