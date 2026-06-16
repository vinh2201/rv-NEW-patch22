package app.revanced.patches.brave.premium

import app.revanced.patcher.accessFlags
import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal val BytecodePatchContext.hasOriginCachedMethod by gettingFirstMethodDeclaratively("brave_origin_credential_summary_cached") {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
    returnType("Z")
}

internal val BytecodePatchContext.braveOriginPreferencesMethodMatch by gettingFirstMethodDeclaratively("show_restart_prompt") {
    definingClass("Lorg/chromium/chrome/browser/settings/BraveOriginPreferences;")
}

internal val BytecodePatchContext.isOriginSubscriptionActiveMethod by gettingFirstMethodDeclaratively("brave.origin.subscription_active_android") {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
    returnType("Z")
}

internal val BytecodePatchContext.isFetchingCredentialsMethod by gettingFirstMethodDeclaratively("brave.origin.order_id_android") {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
    returnType("Z")
}

internal val BytecodePatchContext.requestCredentialSummaryMethod by gettingFirstMethodDeclaratively("requestCredentialSummary profile is null") {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
    returnType("V")
    parameterTypes("Lorg/chromium/chrome/browser/profiles/Profile;", "Lorg/chromium/base/Callback;")
}

internal val BytecodePatchContext.braveLocalStateGetMethod by gettingFirstMethodDeclaratively {
    definingClass("Lorg/chromium/chrome/browser/prefs/LocalStatePrefs;")
    accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
    returnType("Lorg/chromium/components/prefs/PrefService;")
    parameterTypes()
}

internal val BytecodePatchContext.onPrefChangeMethod by gettingFirstMethodDeclaratively {
    definingClass("Lorg/chromium/chrome/browser/settings/BraveOriginPreferences;")
    returnType("Z")
    parameterTypes("Landroidx/preference/Preference;", "Ljava/lang/Object;")
}

internal val BytecodePatchContext.appRestrictionsProviderMethodMatch get() = app.revanced.patcher.fingerprint {
    custom { m, _ ->
        val params = m.parameters.toList()
        if (params.size == 2 && params[0].type == "Landroid/os/UserManager;" && params[1].type == "Ljava/lang/String;" && m.returnType == "Landroid/os/Bundle;") {
            val impl = m.implementation ?: return@custom false
            impl.instructions.any { instr ->
                instr is ReferenceInstruction &&
                (instr.reference as? StringReference)?.string == "cr_AppResProvider"
            }
        } else {
            false
        }
    }
}.method
