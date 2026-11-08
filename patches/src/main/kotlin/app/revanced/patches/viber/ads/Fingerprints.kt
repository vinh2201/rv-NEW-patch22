package app.revanced.patches.viber.ads

import app.revanced.patcher.gettingFirstImmutableMethodOrNull
import app.revanced.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val ADS_FREE_FLAG_STRING = "viber_plus_debug_ads_free_flag"
private const val ADS_CONTROLLER_STRING = "BaseAdsController.EventAdLoadStarted"

/**
 * Method that builds the "[DEBUG] ViberPlus Enable Ads Free feature" feature switch
 * (Viber 23.x - 26.x, and probably the versions in between).
 * Viber 29.x no longer has this class, so this is null there.
 */
internal val BytecodePatchContext.adsFreeFlagInitMethod by gettingFirstImmutableMethodOrNull(ADS_FREE_FLAG_STRING)

/**
 * Any method of the (obfuscated) `BaseAdsController` class. Only used to locate the class,
 * the string is part of the unobfuscated log message of the controller.
 * Present in every Viber version checked (23.4.3.0, 26.1.2.0, 29.3.1.0).
 */
internal val BytecodePatchContext.adsControllerAnchorMethod by gettingFirstImmutableMethodOrNull(ADS_CONTROLLER_STRING)

/**
 * Finds the `()I` method of the ads free feature switch class.
 *
 * The feature switch is created with:
 * ```
 * new-instance v4, LSwitchClass;
 * const-string v5, "viber_plus_debug_ads_free_flag"
 * ```
 * so the string has to be directly preceded by the `new-instance`.
 * Anything else (for example Viber 29.x where the string is only passed to a helper method)
 * is rejected, to avoid patching an unrelated class.
 */
internal fun BytecodePatchContext.findAdsFreeFlagMethod(): Method? {
    val initMethod = adsFreeFlagInitMethod ?: return null
    val instructions = initMethod.implementation?.instructions?.toList() ?: return null

    val stringIndex = instructions.indexOfFirst { instruction ->
        instruction is ReferenceInstruction &&
            (instruction.reference as? StringReference)?.string == ADS_FREE_FLAG_STRING
    }
    if (stringIndex < 1) return null

    val newInstance = instructions[stringIndex - 1]
    if (newInstance.opcode != Opcode.NEW_INSTANCE) return null

    val switchClassType = ((newInstance as ReferenceInstruction).reference as? TypeReference)?.type ?: return null
    val switchClass = classDefs.firstOrNull { it.type == switchClassType } ?: return null

    // The switch class only overrides one method: `public final r()I`.
    return switchClass.methods.singleOrNull { method ->
        method.returnType == "I" &&
            method.parameterTypes.isEmpty() &&
            !AccessFlags.STATIC.isSet(method.accessFlags)
    }
}

/**
 * Finds the method of `BaseAdsController` that tells if ads are allowed:
 * ```
 * public final isAdsAllowed()Z {
 *     if (this.someCondition() && this.config.getType() != SomeEnum.X) return ...
 *     return ...
 * }
 * ```
 * It is `public final`, takes no parameters, returns boolean, calls another `()Z` method of its own class,
 * reads a static enum field and compares it with `if-eq`.
 * Obfuscated names change on every version, the shape of the method doesn't.
 */
internal fun BytecodePatchContext.findAdsAllowedMethod(): Method? {
    val anchor = adsControllerAnchorMethod ?: return null
    val controllerType = anchor.definingClass
    val controllerClass = classDefs.firstOrNull { it.type == controllerType } ?: return null

    return controllerClass.methods.singleOrNull { method ->
        if (method.returnType != "Z" || method.parameterTypes.isNotEmpty()) return@singleOrNull false

        val flags = method.accessFlags
        if (!AccessFlags.PUBLIC.isSet(flags) || !AccessFlags.FINAL.isSet(flags) || AccessFlags.STATIC.isSet(flags)) {
            return@singleOrNull false
        }

        val instructions = method.implementation?.instructions?.toList() ?: return@singleOrNull false

        instructions.any { it.opcode == Opcode.IF_EQ } &&
            instructions.any { it.opcode == Opcode.SGET_OBJECT } &&
            instructions.any { instruction ->
                if (instruction.opcode != Opcode.INVOKE_VIRTUAL) return@any false
                val reference = (instruction as ReferenceInstruction).reference as? MethodReference
                    ?: return@any false

                reference.definingClass == controllerType &&
                    reference.returnType == "Z" &&
                    reference.parameterTypes.isEmpty()
            }
    }
}
