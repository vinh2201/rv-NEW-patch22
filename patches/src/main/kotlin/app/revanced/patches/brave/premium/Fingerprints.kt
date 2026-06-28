package app.revanced.patches.brave.premium

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.*
import app.revanced.patcher.accessFlags
import app.revanced.patcher.composingFirstMethod
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.opcodes
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

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
    custom {
        immutableClassDef.type == "Lorg/chromium/chrome/browser/prefs/LocalStatePrefs;"
    }
    returnType("Lorg/chromium/components/prefs/PrefService;")
    parameterTypes()
}

internal val BytecodePatchContext.vpnPolicyMethod by gettingFirstMethodDeclaratively("brave.brave_vpn.disabled_by_policy")
internal val BytecodePatchContext.newsPolicyMethod by gettingFirstMethodDeclaratively("brave.news.disabled_by_policy")
internal val BytecodePatchContext.rewardsPolicyMethod by gettingFirstMethodDeclaratively("brave.rewards.disabled_by_policy")
internal val BytecodePatchContext.walletPolicyMethod by gettingFirstMethodDeclaratively("brave.wallet.disabled_by_policy")
internal val BytecodePatchContext.leoPolicyMethod by gettingFirstMethodDeclaratively("brave.ai_chat.enabled_by_policy")

internal val BytecodePatchContext.settingsPromoCardPreferenceClassDef
    get() = firstClassDefOrNull("Lorg/chromium/chrome/browser/ui/settings_promo_card/SettingsPromoCardPreference;")

context(_: BytecodePatchContext)
internal val com.android.tools.smali.dexlib2.iface.ClassDef.promoInitMethod
    get() = firstMethodOrNull { name == "<init>" }

internal fun com.android.tools.smali.dexlib2.iface.ClassDef.getPromoBindMethodMatch() =
    firstMethodComposite {
        returnType("V")
        parameterTypes("L")
        instructions(
            allOf(
                Opcode.INVOKE_VIRTUAL(),
                method {
                    definingClass == "Landroidx/preference/Preference;" &&
                        returnType == "V" &&
                        parameterTypes == listOf("Z")
                },
            ),
        )
    }

internal val BytecodePatchContext.appRestrictionsMethod: Method
    get() =
        classDefs.flatMap { it.methods }.firstMethod {
            val methodParameters = parameters.toList()
            methodParameters.size == 2 &&
                methodParameters[0].type == "Landroid/os/UserManager;" &&
                methodParameters[1].type == "Ljava/lang/String;" &&
                returnType == "Landroid/os/Bundle;" &&
                implementation?.instructions?.any { instruction ->
                    instruction is ReferenceInstruction &&
                        (instruction.reference as? StringReference)?.string == "cr_AppResProvider"
                } == true
        }

internal val com.android.tools.smali.dexlib2.iface.ClassDef.contextFieldName
    get() = fields.first { it.type == "Landroid/content/Context;" }.name

internal val com.android.tools.smali.dexlib2.iface.ClassDef.userManagerFieldName
    get() = fields.first { it.type == "Landroid/os/UserManager;" }.name

internal fun com.android.tools.smali.dexlib2.iface.ClassDef.getAppRestrictionsProviderOnRestrictionsReceivedMethodMatch(
    appRestrictionsMethod: MethodReference,
) = firstMethodComposite {
    returnType("V")
    custom { name != "<init>" && name != appRestrictionsMethod.name }
    instructions(
        allOf(
            Opcode.INVOKE_VIRTUAL(),
            method {
                returnType == "V" &&
                    parameterTypes == listOf("Landroid/os/Bundle;")
            },
        ),
    )
}

internal val BytecodePatchContext.braveOriginPreferencesOnPreferenceChangeMethod: MutableMethod
    get() =
        classDefs.flatMap { it.methods }.firstMethod {
            val methodParameters = parameters.toList()
            immutableClassDef.type == "Lorg/chromium/chrome/browser/settings/BraveOriginPreferences;" &&
                methodParameters.size == 2 &&
                methodParameters[0].type == "Landroidx/preference/Preference;" &&
                methodParameters[1].type == "Ljava/lang/Object;" &&
                returnType == "Z"
        }

internal val BytecodePatchContext.showOriginSettingsForRestartMethod by gettingFirstMethodDeclaratively {
    custom {
        immutableClassDef.type == "Lorg/chromium/chrome/browser/brave_origin/BraveOriginSettingsLauncherHelper;"
    }
    returnType("V")
    parameterTypes()
}
