package app.revanced.patches.viber.misc.onlinestatus

import app.revanced.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val ONLINE_STATUS_CHANGE_LIMIT_MS = 86_400_000L

/** Result register of `longValue()` that is read right after the call. */
internal class OnlineStatusLimit(val method: Method, val moveResultIndex: Int, val register: Int)

private fun Instruction.isCallTo(name: String, definingClasses: Set<String>? = null): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_INTERFACE) return false

    val reference = (this as ReferenceInstruction).reference as? MethodReference ?: return false
    return reference.name == name && (definingClasses == null || reference.definingClass in definingClasses)
}

/**
 * Methods that compare the time of the last online status change with the 24 hours limit:
 * ```
 * long last = ((Number) settings.getLastOnlineLastChangedTime()).longValue();
 * if (now - last < 86_400_000L) ...
 * ```
 * There is one in the settings screen (`void a(Object)`) and one in the preference click listener
 * (`boolean a(Preference)`). Only present in Viber 29.x and newer.
 */
internal fun BytecodePatchContext.findOnlineStatusLimits(): List<OnlineStatusLimit> {
    val result = mutableListOf<OnlineStatusLimit>()

    for (classDef in classDefs) {
        for (method in classDef.methods) {
            val parameters = method.parameterTypes
            val isScreenChange = method.returnType == "V" && parameters.size == 1 && parameters[0].startsWith("L")
            val isPreferenceChange = method.returnType == "Z" &&
                parameters.size == 1 &&
                parameters[0] == "Landroidx/preference/Preference;"
            if (!isScreenChange && !isPreferenceChange) continue

            val instructions = method.implementation?.instructions?.toList() ?: continue

            val lastChangedIndex = instructions.indexOfFirst { it.isCallTo("getLastOnlineLastChangedTime") }
            if (lastChangedIndex < 0) continue

            val longValueIndex = (lastChangedIndex + 1 until instructions.size).firstOrNull {
                instructions[it].isCallTo("longValue", setOf("Ljava/lang/Number;", "Ljava/lang/Long;"))
            } ?: continue

            val hasLimit = (longValueIndex + 1 until instructions.size).any {
                (instructions[it] as? WideLiteralInstruction)?.wideLiteral == ONLINE_STATUS_CHANGE_LIMIT_MS
            }
            if (!hasLimit) continue

            val moveResult = instructions.getOrNull(longValueIndex + 1) ?: continue
            if (moveResult.opcode != Opcode.MOVE_RESULT_WIDE) continue

            result += OnlineStatusLimit(method, longValueIndex + 1, (moveResult as OneRegisterInstruction).registerA)
        }
    }

    return result
}
