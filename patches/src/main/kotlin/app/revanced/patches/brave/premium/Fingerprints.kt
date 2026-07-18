package app.revanced.patches.brave.premium

import app.revanced.patcher.*
import app.revanced.patcher.firstClassDef
import app.revanced.patcher.firstMethod
import app.revanced.patcher.firstMethodDeclaratively
import app.revanced.patcher.opcodes
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal fun BytecodePatchContext.getHasOriginCachedMethod() = firstMethodDeclaratively("brave_origin_credential_summary_cached") {
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

internal fun BytecodePatchContext.getIsOriginSubscriptionActiveMethod() = firstMethodDeclaratively(
    "brave.origin.subscription_active_android",
) {
    returnType("Z")
}

internal fun BytecodePatchContext.getIsFetchingCredentialsMethod() = firstMethodDeclaratively("brave.origin.order_id_android") {
    returnType("Z")
}

internal fun BytecodePatchContext.getRequestCredentialSummaryMethod() = firstMethodDeclaratively(
    "requestCredentialSummary profile is null",
)

internal fun BytecodePatchContext.getBraveLocalStateGetMethod() = firstMethodDeclaratively {
    definingClass("Lorg/chromium/chrome/browser/prefs/LocalStatePrefs;")
    returnType("Lorg/chromium/components/prefs/PrefService;")
    parameterTypes()
}

internal fun BytecodePatchContext.getSettingsPromoCardPreferenceClassDef() =
    firstClassDef("Lorg/chromium/chrome/browser/ui/settings_promo_card/SettingsPromoCardPreference;")

internal fun ClassDef.getConstructorMethod() =
    methods.first { it.name == "<init>" }

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

internal fun BytecodePatchContext.getAppRestrictionsMethod() = firstMethodDeclaratively("cr_AppResProvider") {
    returnType("Landroid/os/Bundle;")
    parameterTypes("Landroid/os/UserManager;", "Ljava/lang/String;")
}

internal fun ClassDef.getContextField() =
    fields.first { it.type == "Landroid/content/Context;" }

internal fun ClassDef.getAppRestrictionsProviderOnRestrictionsReceivedMethodMatch(
    appRestrictionsMethod: MethodReference,
) = firstMethodComposite {
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

internal fun BytecodePatchContext.getBraveOriginPreferencesOnPreferenceChangeMethod() = firstMethodDeclaratively {
    definingClass("Lorg/chromium/chrome/browser/settings/BraveOriginPreferences;")
    returnType("Z")
    parameterTypes("Landroidx/preference/Preference;", "Ljava/lang/Object;")
}

internal fun BytecodePatchContext.getShowOriginSettingsForRestartMethod() = firstMethodDeclaratively {
    definingClass("Lorg/chromium/chrome/browser/brave_origin/BraveOriginSettingsLauncherHelper;")
    returnType("V")
    parameterTypes()
}
