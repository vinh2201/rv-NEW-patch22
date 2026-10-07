package app.revanced.patches.brave.premium

import app.revanced.patcher.*
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal val BytecodePatchContext.hasOriginCachedMethod by gettingFirstMethodDeclaratively("brave_origin_credential_summary_cached") {
    returnType("Z")
}

internal val BytecodePatchContext.onCreatePreferencesMethodMatch by composingFirstMethod("show_restart_prompt") {
    opcodes(
        Opcode.CONST_STRING,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT,
        Opcode.IPUT_BOOLEAN,
        Opcode.INVOKE_STATIC,
        Opcode.MOVE_RESULT,
        Opcode.IF_EQZ,
    )
}

internal val BytecodePatchContext.isOriginSubscriptionActiveMethod by gettingFirstMethodDeclaratively("brave.origin.subscription_active_android") {
    returnType("Z")
}

internal val BytecodePatchContext.requestCredentialSummaryMethod by gettingFirstMethodDeclaratively("SkusService is null, cannot request credential summary")

internal val BytecodePatchContext.braveLocalStateGetMethod by gettingFirstMethodDeclaratively {
    definingClass("Lorg/chromium/chrome/browser/prefs/LocalStatePrefs;")
    returnType("Lorg/chromium/components/prefs/PrefService;")
    parameterTypes()
}

internal val BytecodePatchContext.prefServiceSetBooleanMethod by gettingFirstMethodDeclaratively {
    definingClass("Lorg/chromium/components/prefs/PrefService;")
    returnType("V")
    parameterTypes("Ljava/lang/String;", "Z")
}

internal val BytecodePatchContext.appRestrictionsMethod by gettingFirstImmutableMethodDeclaratively("cr_AppResProvider") {
    returnType("Landroid/os/Bundle;")
    parameterTypes("Landroid/os/UserManager;", "Ljava/lang/String;")
}
internal fun BytecodePatchContext.getAppRestrictionsProviderOnRestrictionsReceivedMethodMatch(
    appRestrictionsMethod: MethodReference,
) = firstMethodComposite {
    definingClass(appRestrictionsMethod.definingClass)
    returnType("V")
    name { this != "<init>" && this != appRestrictionsMethod.name }
    instructions(
        allOf(
            Opcode.INVOKE_VIRTUAL(),
            method {
                returnType == "V" &&
                    parameterTypes.size == 1 && parameterTypes[0] == "Landroid/os/Bundle;"
            },
        ),
    )
}

internal val BytecodePatchContext.braveOriginPreferencesOnPreferenceChangeMethodMatch by composingFirstMethod("BraveWalletDisabled") {
    definingClass("Lorg/chromium/chrome/browser/settings/BraveOriginPreferences;")
    returnType("Z")
    parameterTypes("Landroidx/preference/Preference;", "Ljava/lang/Object;")
    instructions(
        method {
            parameterTypes.size == 3 &&
                parameterTypes[0] == "Ljava/lang/String;" &&
                parameterTypes[1] == "Z"
        },
    )
}

internal val BytecodePatchContext.showOriginSettingsForRestartMethod by gettingFirstMethodDeclaratively {
    definingClass("Lorg/chromium/chrome/browser/brave_origin/BraveOriginSettingsLauncherHelper;")
    returnType("V")
    parameterTypes()
}
