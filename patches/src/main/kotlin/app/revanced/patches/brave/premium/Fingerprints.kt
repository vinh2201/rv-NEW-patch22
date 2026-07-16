package app.revanced.patches.brave.premium

import app.revanced.patcher.*
import app.revanced.patcher.accessFlags
import app.revanced.patcher.composingFirstMethod
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.opcodes
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal val BytecodePatchContext.hasOriginCachedMethod by gettingFirstMethodDeclaratively("brave_origin_credential_summary_cached") {
    returnType("Z")
}

internal val BytecodePatchContext.onCreatePreferencesMethod by composingFirstMethod("show_restart_prompt") {
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

internal val BytecodePatchContext.onOriginSubscriptionMethod by gettingFirstMethodDeclaratively("brave.origin.package_name_android") {
    returnType("V")
    parameterTypes("Ljava/lang/Object;")
}

internal val BytecodePatchContext.isOriginSubscriptionActiveMethod by gettingFirstMethodDeclaratively(
    "brave.origin.subscription_active_android",
) {
    returnType("Z")
}

internal val BytecodePatchContext.isFetchingCredentialsMethod by gettingFirstMethodDeclaratively("brave.origin.order_id_android") {
    returnType("Z")
}

internal val BytecodePatchContext.requestCredentialSummaryMethod by gettingFirstMethodDeclaratively(
    "requestCredentialSummary profile is null",
)

internal val BytecodePatchContext.braveLocalStateGetMethod by gettingFirstMethodDeclaratively {
    definingClass("Lorg/chromium/chrome/browser/prefs/LocalStatePrefs;")
    returnType("Lorg/chromium/components/prefs/PrefService;")
    parameterTypes()
}


internal val BytecodePatchContext.settingsPromoCardPreferenceClassDef
    get() = firstClassDef("Lorg/chromium/chrome/browser/ui/settings_promo_card/SettingsPromoCardPreference;")

context(_: BytecodePatchContext)
internal fun ClassDef.getPromoInitMethod() =
    firstMethod { name == "<init>" }

internal fun ClassDef.getPromoBindMethodMatch() =
    firstMethodComposite {
        returnType("V")
        parameterTypes("L")
        instructions(
            allOf(
                Opcode.INVOKE_VIRTUAL(),
                method {
                    definingClass == "Landroidx/preference/Preference;" &&
                        returnType == "V" &&
                        parameterTypes.size == 1 && parameterTypes[0] == "Z"
                },
            ),
        )
    }

internal val BytecodePatchContext.appRestrictionsMethod by gettingFirstMethodDeclaratively("cr_AppResProvider") {
    returnType("Landroid/os/Bundle;")
    parameterTypes("Landroid/os/UserManager;", "Ljava/lang/String;")
}


internal val ClassDef.contextFieldName
    get() = fields.first { it.type == "Landroid/content/Context;" }.name

internal val ClassDef.userManagerFieldName
    get() = fields.first { it.type == "Landroid/os/UserManager;" }.name

internal fun ClassDef.getAppRestrictionsProviderOnRestrictionsReceivedMethodMatch(
    appRestrictionsMethod: MethodReference,
) = firstMethodComposite {
    returnType("V")
    custom { name != "<init>" && name != appRestrictionsMethod.name }
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

internal val BytecodePatchContext.braveOriginPreferencesOnPreferenceChangeMethod by gettingFirstMethodDeclaratively {
    definingClass("Lorg/chromium/chrome/browser/settings/BraveOriginPreferences;")
    returnType("Z")
    parameterTypes("Landroidx/preference/Preference;", "Ljava/lang/Object;")
}

internal val BytecodePatchContext.showOriginSettingsForRestartMethod by gettingFirstMethodDeclaratively {
    definingClass("Lorg/chromium/chrome/browser/brave_origin/BraveOriginSettingsLauncherHelper;")
    returnType("V")
    parameterTypes()
}
