package app.revanced.patches.viki.ads

import app.revanced.patcher.accessFlags
import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.AccessFlags

internal val BytecodePatchContext.videoAdsMethod by gettingFirstMethodDeclaratively {
    name("V5")
    definingClass("m6;")
    accessFlags(AccessFlags.PRIVATE, AccessFlags.FINAL)
    returnType("Z")
}