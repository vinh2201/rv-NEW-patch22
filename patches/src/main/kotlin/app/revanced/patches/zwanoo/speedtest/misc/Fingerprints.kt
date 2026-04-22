package app.revanced.patches.zwanoo.speedtest.misc

import app.revanced.patcher.accessFlags
import app.revanced.patcher.custom
import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import app.revanced.patcher.immutableClassDef

internal val BytecodePatchContext.subscriptionExpiryMethod by gettingFirstMethodDeclaratively("StUserSubscription(subscriptionTypeName=") {
    definingClass("Lcom/ookla/speedtest/useraccounts/StUserSubscription;")
    returnType("Ljava/lang/String;")
    accessFlags(AccessFlags.PUBLIC)
    custom {
        val igetInstruction = implementation?.instructions
            ?.firstOrNull { it.opcode == Opcode.IGET_OBJECT }
            ?: return@custom false
        val fieldReference = (igetInstruction as? ReferenceInstruction)?.reference?.toString()
            ?: return@custom false
        val fieldName = fieldReference.substringAfter("->").substringBefore(":")

        val lastStringField = immutableClassDef.instanceFields
            .filter { it.type == "Ljava/lang/String;" }
            .maxByOrNull { it.name }
            ?: return@custom false

        fieldName == lastStringField.name && name != fieldName
    }
}

internal val BytecodePatchContext.showUpgradeDialogMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/ookla/speedtest/app/userprompt/")
    returnType("V")
    accessFlags(AccessFlags.PUBLIC)
    custom {
        parameters.isEmpty() &&
        annotations.none { it.type == "Landroidx/lifecycle/OnLifecycleEvent;" } &&
        implementation?.instructions?.any { instruction ->
            instruction.opcode == Opcode.INVOKE_INTERFACE &&
            (instruction as? ReferenceInstruction)?.reference?.toString()?.contains("speedtest/app/userprompt") == true
        } ?: false
    }
}
