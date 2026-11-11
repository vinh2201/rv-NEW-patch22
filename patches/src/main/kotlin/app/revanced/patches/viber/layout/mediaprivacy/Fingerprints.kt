package app.revanced.patches.viber.layout.mediaprivacy

import app.revanced.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val SECRET_CHAT_EVENT_MESSAGE = "Lcom/viber/jni/im2/CSecretChatSendEventMsg;"

/**
 * `void onConversationScreenShotTaken(Event)`: sends the screenshot alert of the conversation.
 * Present in Viber 23.x, 26.x and 29.x.
 */
internal fun BytecodePatchContext.findScreenshotTakenMethod(): Method? {
    for (classDef in classDefs) {
        for (method in classDef.methods) {
            if (method.name != "onConversationScreenShotTaken" || method.returnType != "V") continue

            val sendsSequence = method.implementation?.instructions?.any { instruction ->
                val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                reference?.definingClass == "Lcom/viber/jni/controller/PhoneController;" &&
                    reference.name == "generateSequence"
            } == true

            if (sendsSequence) return method
        }
    }

    return null
}

internal class SecretChatEventSender(val method: Method, val eventTypeRegister: Int)

/**
 * The method that sends a secret chat event (screenshot taken, ...) to the server:
 * ```
 * void send(int sequence, long token, int ?, int eventType, String group)
 * ```
 * The parameters are in a different order in each Viber version. The event type is the one that is
 * converted to `byte` before creating the `CSecretChatSendEventMsg`.
 */
internal fun BytecodePatchContext.findSecretChatEventSender(): SecretChatEventSender? {
    for (classDef in classDefs) {
        for (method in classDef.methods) {
            if (method.returnType != "V" || AccessFlags.STATIC.isSet(method.accessFlags)) continue

            val instructions = method.implementation?.instructions?.toList() ?: continue

            val createsEvent = instructions.any {
                it.opcode == Opcode.NEW_INSTANCE &&
                    ((it as ReferenceInstruction).reference as? TypeReference)?.type == SECRET_CHAT_EVENT_MESSAGE
            }
            if (!createsEvent) continue

            val toByte = instructions.firstOrNull { it.opcode == Opcode.INT_TO_BYTE } ?: continue
            return SecretChatEventSender(method, (toByte as TwoRegisterInstruction).registerB)
        }
    }

    return null
}
