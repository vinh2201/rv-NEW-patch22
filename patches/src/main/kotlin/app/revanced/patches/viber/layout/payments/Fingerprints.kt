package app.revanced.patches.viber.layout.payments

import app.revanced.patcher.gettingFirstImmutableMethodOrNull
import app.revanced.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * `toString()` of the menu settings of the chat: "MenuSettings(showRakutenSendMoneyItem=...)".
 * Present in Viber 23.x, 26.x and 29.x.
 */
internal val BytecodePatchContext.sendMoneySettingsToStringMethod by gettingFirstImmutableMethodOrNull(
    "MenuSettings(showRakutenSendMoneyItem=",
)

/**
 * `<clinit>` of the attachment types enum, which creates RAKUTEN_BANK and VIBER_PAY.
 */
internal val BytecodePatchContext.attachmentTypesInitMethod by gettingFirstImmutableMethodOrNull(
    "RAKUTEN_BANK",
    "VIBER_PAY",
)

/** The constructor `<init>(boolean showRakutenSendMoneyItem)`. */
internal fun BytecodePatchContext.findSendMoneyConstructor(): Method? {
    val anchor = sendMoneySettingsToStringMethod ?: return null
    val settingsClass = classDefs.firstOrNull { it.type == anchor.definingClass } ?: return null

    return settingsClass.methods.singleOrNull { method ->
        method.name == "<init>" && method.parameterTypes.size == 1 && method.parameterTypes[0] == "Z"
    }
}

internal class PaymentAttachmentFields(val bank: FieldReference, val pay: FieldReference)

/**
 * The enum fields of RAKUTEN_BANK and VIBER_PAY. Each one is stored with the first `sput-object`
 * after the string with its name.
 */
internal fun BytecodePatchContext.findPaymentAttachmentFields(): PaymentAttachmentFields? {
    val initMethod = attachmentTypesInitMethod ?: return null
    if (initMethod.name != "<clinit>") return null

    val instructions = initMethod.implementation?.instructions?.toList() ?: return null

    fun fieldStoredAfter(name: String): FieldReference? {
        val stringIndex = instructions.indexOfFirst {
            it is ReferenceInstruction && (it.reference as? StringReference)?.string == name
        }
        if (stringIndex < 0) return null

        val store = (stringIndex until instructions.size).firstOrNull {
            instructions[it].opcode == Opcode.SPUT_OBJECT
        } ?: return null

        return (instructions[store] as ReferenceInstruction).reference as? FieldReference
    }

    val bank = fieldStoredAfter("RAKUTEN_BANK") ?: return null
    val pay = fieldStoredAfter("VIBER_PAY") ?: return null

    return PaymentAttachmentFields(bank, pay)
}

internal class AttachmentListReturn(val method: Method, val returnIndex: Int, val register: Int)

/**
 * The method that returns the list of attachments of the chat: it reads the RAKUTEN_BANK field and
 * then returns the list.
 */
internal fun BytecodePatchContext.findAttachmentListReturn(bank: FieldReference): AttachmentListReturn? {
    for (classDef in classDefs) {
        for (method in classDef.methods) {
            if (method.returnType != "Ljava/util/List;" || method.parameterTypes.isNotEmpty()) continue

            val instructions = method.implementation?.instructions?.toList() ?: continue

            val bankIndex = instructions.indexOfFirst {
                it.opcode == Opcode.SGET_OBJECT && (it as ReferenceInstruction).reference == bank
            }
            if (bankIndex < 0) continue

            val returnIndex = (bankIndex + 1 until instructions.size).firstOrNull {
                instructions[it].opcode == Opcode.RETURN_OBJECT
            } ?: continue

            return AttachmentListReturn(method, returnIndex, (instructions[returnIndex] as OneRegisterInstruction).registerA)
        }
    }

    return null
}
