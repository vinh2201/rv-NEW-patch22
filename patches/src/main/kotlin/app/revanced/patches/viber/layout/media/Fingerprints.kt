package app.revanced.patches.viber.layout.media

import app.revanced.patcher.gettingFirstImmutableMethodOrNull
import app.revanced.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** Message flag of disappearing messages. */
private const val DISAPPEARING_MESSAGE_FLAG = 27

private const val CONVERSATION_ENTITY = "Lcom/viber/voip/messages/conversation/ConversationItemLoaderEntity;"

/**
 * `toString()` of the `MediaDetailsMenuState` class, the state of the menu of the media viewer.
 * Present in Viber 23.x, 26.x and 29.x.
 */
internal val BytecodePatchContext.mediaMenuStateToStringMethod by gettingFirstImmutableMethodOrNull(
    "MediaDetailsMenuState(setLockScreenVisible=",
)

internal class DisappearingMediaCheck(val method: Method, val moveResultIndex: Int, val register: Int)

private fun Instruction.isFlagCheck(): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL) return false

    val reference = (this as ReferenceInstruction).reference as? MethodReference ?: return false
    return reference.name == "a" &&
        reference.returnType == "Z" &&
        reference.parameterTypes.size == 1 &&
        reference.parameterTypes[0] == "I"
}

private fun Instruction.isNotShareablePublicAccountCall(): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return false

    return ((this as ReferenceInstruction).reference as? MethodReference)?.name == "isNotShareablePublicAccount"
}

/**
 * Finds the method that builds the menu state:
 * ```
 * a(Message, ConversationItemLoaderEntity, boolean)MediaDetailsMenuState
 * ```
 * and the result of the second `flags.a(27)` call, which checks if the message is a disappearing message.
 * This is the same in Viber 23.x, 26.x and 29.x.
 */
internal fun BytecodePatchContext.findDisappearingMediaCheck(): DisappearingMediaCheck? {
    val menuStateType = mediaMenuStateToStringMethod?.definingClass ?: return null

    for (classDef in classDefs) {
        for (method in classDef.methods) {
            if (method.returnType != menuStateType) continue

            val parameters = method.parameterTypes
            if (parameters.size != 3 || parameters[1] != CONVERSATION_ENTITY || parameters[2] != "Z") continue

            val instructions = method.implementation?.instructions?.toList() ?: continue

            val flagIndex = instructions.indexOfFirst {
                (it.opcode == Opcode.CONST_4 || it.opcode == Opcode.CONST_16 || it.opcode == Opcode.CONST) &&
                    (it as NarrowLiteralInstruction).narrowLiteral == DISAPPEARING_MESSAGE_FLAG
            }
            if (flagIndex < 0) continue

            val firstCheck = (flagIndex + 1 until instructions.size).firstOrNull { instructions[it].isFlagCheck() }
                ?: continue
            val secondCheck = (firstCheck + 1 until instructions.size).firstOrNull { instructions[it].isFlagCheck() }
                ?: continue

            if ((secondCheck + 1 until instructions.size).none { instructions[it].isNotShareablePublicAccountCall() }) {
                continue
            }

            val moveResult = instructions.getOrNull(secondCheck + 1) ?: continue
            if (moveResult.opcode != Opcode.MOVE_RESULT) continue

            return DisappearingMediaCheck(method, secondCheck + 1, (moveResult as OneRegisterInstruction).registerA)
        }
    }

    return null
}
