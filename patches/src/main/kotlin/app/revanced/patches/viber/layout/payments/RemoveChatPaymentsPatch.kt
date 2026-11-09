package app.revanced.patches.viber.layout.payments

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

@Suppress("unused")
val removeChatPaymentsPatch = bytecodePatch(
    name = "Remove chat payment actions",
    description = "(PORTED) Removes payment actions from chat menus. Works with Viber 23.x, 26.x and 29.x.",
) {
    compatibleWith("com.viber.voip")

    apply {
        // "Send money" menu item.
        val sendMoneyConstructor = findSendMoneyConstructor()
            ?: throw PatchException("Could not find the chat menu settings. This Viber version is not supported.")

        firstMethod(sendMoneyConstructor).addInstructions(0, "const/4 p1, 0x0")

        // Rakuten bank and Viber Pay attachments.
        val fields = findPaymentAttachmentFields()
            ?: throw PatchException("Could not find the attachment types. This Viber version is not supported.")
        val listReturn = findAttachmentListReturn(fields.bank)
            ?: throw PatchException("Could not find the attachments list. This Viber version is not supported.")

        val method = firstMethod(listReturn.method)

        // Nothing is alive when the method returns, so any other register can be used.
        val registerCount = method.implementation!!.registerCount
        val temporaryRegisters = (0 until minOf(registerCount, 16)).filter { it != listReturn.register }
        if (listReturn.register > 15 || temporaryRegisters.size < 2) {
            throw PatchException("No free registers in the attachments list method.")
        }

        val copy = temporaryRegisters[0]
        val item = temporaryRegisters[1]

        method.addInstructions(
            listReturn.returnIndex,
            """
                new-instance v$copy, Ljava/util/ArrayList;
                invoke-direct { v$copy, v${listReturn.register} }, Ljava/util/ArrayList;-><init>(Ljava/util/Collection;)V
                sget-object v$item, ${fields.bank.smali()}
                invoke-virtual { v$copy, v$item }, Ljava/util/ArrayList;->remove(Ljava/lang/Object;)Z
                sget-object v$item, ${fields.pay.smali()}
                invoke-virtual { v$copy, v$item }, Ljava/util/ArrayList;->remove(Ljava/lang/Object;)Z
                move-object v${listReturn.register}, v$copy
            """,
        )
    }
}

private fun FieldReference.smali() = "$definingClass->$name:$type"
