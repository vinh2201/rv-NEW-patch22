package app.revanced.patches.googlephotos.misc.gms

import app.revanced.patcher.accessFlags
import app.revanced.patcher.definingClass
import app.revanced.patcher.firstMethodDeclaratively
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import app.revanced.patcher.strings
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef

internal val BytecodePatchContext.homeActivityOnCreateMethod by gettingFirstMethodDeclaratively {
    name("onCreate")
    definingClass("/HomeActivity;")
}

internal val BytecodePatchContext.googleAuthGetAccountsMethod by gettingFirstMethodDeclaratively {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
    returnType("[Landroid/accounts/Account;")
    parameterTypes("Landroid/content/Context;")
    strings("get_accounts")
}

context(_: BytecodePatchContext)
internal fun ClassDef.getGoogleAuthGetAccountsWithFeaturesMethod() = firstMethodDeclaratively {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
    returnType("[Landroid/accounts/Account;")
    parameterTypes("Landroid/content/Context;", "[Ljava/lang/String;")
}
