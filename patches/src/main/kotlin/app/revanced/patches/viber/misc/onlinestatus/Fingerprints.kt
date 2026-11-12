package app.revanced.patches.viber.misc.onlinestatus

import app.revanced.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction

private const val ONLINE_STATUS_CHANGE_LIMIT_MS = 86_400_000L

/** The time of the last change of the online status, read from the settings (`move-result-wide`). */
internal class OnlineStatusLimit(val method: Method, val moveResultIndex: Int, val register: Int)

private fun Instruction.usesRegister(register: Int): Boolean {
    val registers = mutableSetOf<Int>()

    if (this is OneRegisterInstruction) registers += registerA
    if (this is TwoRegisterInstruction) registers += registerB
    if (this is ThreeRegisterInstruction) registers += registerC
    if (this is FiveRegisterInstruction) {
        registers += listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    }
    if (this is RegisterRangeInstruction) {
        for (rangeRegister in startRegister until startRegister + registerCount) registers += rangeRegister
    }

    // A wide value uses a pair of registers.
    return register in registers || (register - 1) in registers
}

private fun Instruction.endsStraightLine(): Boolean {
    val name = opcode.name
    return name.startsWith("IF_") || name.startsWith("GOTO") || name.contains("SWITCH") ||
        name == "THROW" || name.startsWith("RETURN")
}

/**
 * Viber changes the online status with a limit of one change every 24 hours. There are two places that check it,
 * in every Viber version checked (23.x to 29.x), with the same shape and different obfuscated names:
 * ```
 * // The view model of the "Online status" screen: `void check(State)`.
 * long last = settings.getLastOnlineLastChangedTime();    // move-result-wide v5
 * ...
 * long elapsed = now() - last;                            // sub-long/2addr v7, v5
 * if (elapsed < 86_400_000L) ...                          // const-wide/32 v9; cmp-long; if-ltz; const/4
 *
 * // The click of the "Online status" preference: `boolean onPreferenceTreeClick(Preference)`.
 * long elapsed = System.currentTimeMillis() - last;       // sub-long/2addr, const-wide/32 86_400_000L
 * ```
 * Returns the `move-result-wide` of the last change in both: if it is zero, the limit has always passed.
 */
internal fun BytecodePatchContext.findOnlineStatusLimits(): List<OnlineStatusLimit> {
    val result = mutableListOf<OnlineStatusLimit>()

    for (classDef in classDefs) {
        for (method in classDef.methods) {
            val instructions = method.implementation?.instructions?.toList() ?: continue

            val isPreferenceClick = method.returnType == "Z" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == "Landroidx/preference/Preference;"

            for (index in 0 until instructions.size - 4) {
                if (instructions[index].opcode != Opcode.SUB_LONG_2ADDR) continue
                if ((instructions[index + 1] as? WideLiteralInstruction)?.wideLiteral != ONLINE_STATUS_CHANGE_LIMIT_MS) continue

                val isViewModelCheck = instructions[index + 2].opcode == Opcode.CMP_LONG &&
                    instructions[index + 3].opcode == Opcode.IF_LTZ &&
                    instructions[index + 4].opcode == Opcode.CONST_4
                if (!isPreferenceClick && !isViewModelCheck) continue

                // The register that is subtracted is the time of the last change.
                val lastChanged = (instructions[index] as TwoRegisterInstruction).registerB

                var definition = -1
                for (previous in index - 1 downTo 0) {
                    val instruction = instructions[previous]

                    if (instruction.opcode == Opcode.MOVE_RESULT_WIDE &&
                        (instruction as OneRegisterInstruction).registerA == lastChanged
                    ) {
                        definition = previous
                        break
                    }

                    if (instruction.endsStraightLine() || instruction.usesRegister(lastChanged)) break
                }
                if (definition < 0) continue

                result += OnlineStatusLimit(method, definition, lastChanged)
            }
        }
    }

    return result
}
