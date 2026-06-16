package app.revanced.patches.brave.premium

import app.revanced.patcher.*
import app.revanced.patcher.accessFlags
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import app.revanced.patcher.composingFirstMethod
import app.revanced.patcher.opcodes
import app.revanced.patcher.parameterTypes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
internal val BytecodePatchContext.hasOriginCachedMethod by gettingFirstMethodDeclaratively("brave_origin_credential_summary_cached") {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
    returnType("Z")
}

internal val BytecodePatchContext.braveOriginPreferencesMethodMatch by composingFirstMethod {
    opcodes(
        Opcode.CONST_STRING,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT,
        Opcode.IPUT_BOOLEAN,
        Opcode.INVOKE_STATIC,
        Opcode.MOVE_RESULT,
        Opcode.IF_EQZ
    )
}

internal val BytecodePatchContext.originSubscriptionCallbackMethod by gettingFirstMethodDeclaratively("brave.origin.package_name_android") {
    returnType("V")
    parameterTypes("Ljava/lang/Object;")
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
}

internal val BytecodePatchContext.braveLocalStateGetMethod by gettingFirstMethodDeclaratively {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
    returnType("Lorg/chromium/components/prefs/PrefService;")
    parameterTypes()
}

internal val BytecodePatchContext.vpnPolicyMethod by gettingFirstMethodDeclaratively("brave.brave_vpn.disabled_by_policy") {
    returnType("Z")
}
internal val BytecodePatchContext.newsPolicyMethod by gettingFirstMethodDeclaratively("brave.news.disabled_by_policy") {
    returnType("Z")
}
internal val BytecodePatchContext.rewardsPolicyMethod by gettingFirstMethodDeclaratively("brave.rewards.disabled_by_policy") {
    returnType("Z")
}
internal val BytecodePatchContext.walletPolicyMethod by gettingFirstMethodDeclaratively("brave.wallet.disabled_by_policy") {
    returnType("Z")
}
internal val BytecodePatchContext.leoPolicyMethod by gettingFirstMethodDeclaratively("brave.ai_chat.enabled_by_policy") {
    returnType("Z")
}
