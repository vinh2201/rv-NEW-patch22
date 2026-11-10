package app.revanced.patches.viber.misc.secondary

import app.revanced.patcher.gettingFirstImmutableMethodOrNull
import app.revanced.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** `<clinit>` of the class that holds the default value of the "use_as_secondary" setting. */
internal val BytecodePatchContext.secondaryModeDefaultAnchor by gettingFirstImmutableMethodOrNull("use_as_secondary")

/** `void setSecondaryMode(boolean)`, it logs "Secondary state was changed to ". */
internal val BytecodePatchContext.setSecondaryModeAnchor by gettingFirstImmutableMethodOrNull(
    "Secondary state was changed to ",
)

/** A boolean result (`move-result`) that has to be replaced. */
internal class BooleanResult(val method: Method, val moveResultIndex: Int, val register: Int)

private fun Instruction.isTabletCall(tabletCheck: MethodReference): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return false
    return (this as ReferenceInstruction).reference == tabletCheck
}

internal class SecondaryModeDefault(val tabletCheck: MethodReference, val result: BooleanResult)

/**
 * ```
 * static { DEFAULT = DeviceType.isTablet(); ... "use_as_secondary" ... }
 * ```
 * The result of the tablet check is the default value of the secondary mode.
 */
internal fun BytecodePatchContext.findSecondaryModeDefault(): SecondaryModeDefault? {
    val method = secondaryModeDefaultAnchor?.takeIf { it.name == "<clinit>" } ?: return null
    val instructions = method.implementation?.instructions?.toList() ?: return null

    val stringIndex = instructions.indexOfFirst {
        it is ReferenceInstruction && (it.reference as? StringReference)?.string == "use_as_secondary"
    }
    if (stringIndex < 0) return null

    for (index in 0 until stringIndex - 1) {
        val call = instructions[index]
        if (call.opcode != Opcode.INVOKE_VIRTUAL) continue

        val reference = (call as ReferenceInstruction).reference as? MethodReference ?: continue
        if (reference.returnType != "Z" || reference.parameterTypes.isNotEmpty()) continue

        val moveResult = instructions[index + 1]
        if (moveResult.opcode != Opcode.MOVE_RESULT) continue

        return SecondaryModeDefault(
            reference,
            BooleanResult(method, index + 1, (moveResult as OneRegisterInstruction).registerA),
        )
    }

    return null
}

/**
 * Places that reset the secondary mode with the result of the tablet check:
 * ```
 * invoke-virtual { v0 }, DeviceType->isTablet()Z
 * move-result v0
 * invoke-virtual { p0, v0 }, Registration->setSecondaryMode(Z)V
 * ```
 * There are 4 in every Viber version checked (23.x, 26.x, 29.x).
 */
internal fun BytecodePatchContext.findSecondaryModeResets(tabletCheck: MethodReference): List<BooleanResult> {
    val setSecondaryMode = setSecondaryModeAnchor?.takeIf {
        it.returnType == "V" && it.parameterTypes.size == 1 && it.parameterTypes[0] == "Z"
    } ?: return emptyList()

    val result = mutableListOf<BooleanResult>()

    for (classDef in classDefs) {
        for (method in classDef.methods) {
            val instructions = method.implementation?.instructions?.toList() ?: continue

            for (index in 0 until instructions.size - 2) {
                if (!instructions[index].isTabletCall(tabletCheck)) continue

                val moveResult = instructions[index + 1]
                val setCall = instructions[index + 2]
                if (moveResult.opcode != Opcode.MOVE_RESULT) continue
                if (setCall !is ReferenceInstruction || setCall.reference != setSecondaryMode) continue

                result += BooleanResult(method, index + 1, (moveResult as OneRegisterInstruction).registerA)
            }
        }
    }

    return result
}

/**
 * The method that tells the platform name sent to Viber ("AndroidTablet" or "Android").
 * It calls the static method that tells if the device is registered as secondary.
 */
internal fun BytecodePatchContext.findSecondaryModeGetter(): MethodReference? {
    val platformName = classDefs.firstNotNullOfOrNull { classDef ->
        classDef.methods.firstOrNull { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) &&
                method.returnType == "Ljava/lang/String;" &&
                method.parameterTypes.isEmpty() &&
                method.implementation?.instructions?.let { instructions ->
                    val strings = instructions
                        .mapNotNull { (it as? ReferenceInstruction)?.reference as? StringReference }
                        .map { it.string }
                    "AndroidTablet" in strings && "Android" in strings
                } == true
        }
    } ?: return null

    return platformName.implementation?.instructions?.firstNotNullOfOrNull { instruction ->
        if (instruction.opcode != Opcode.INVOKE_STATIC) return@firstNotNullOfOrNull null

        val reference = (instruction as ReferenceInstruction).reference as? MethodReference
        reference?.takeIf { it.returnType == "Z" && it.parameterTypes.isEmpty() }
    }
}

/**
 * The code that reads the "send_tablet_device_type_on_registration" resource:
 * ```
 * const v4, 0x7f050021
 * invoke-virtual { v0, v4 }, Resources->getBoolean(I)Z
 * move-result v4
 * ```
 */
internal fun BytecodePatchContext.findRegistrationDeviceType(resourceId: Long): BooleanResult? {
    for (classDef in classDefs) {
        for (method in classDef.methods) {
            val instructions = method.implementation?.instructions?.toList() ?: continue

            for (index in 0 until instructions.size - 2) {
                val literal = instructions[index]
                if (literal.opcode != Opcode.CONST || (literal as NarrowLiteralInstruction).narrowLiteral != resourceId.toInt()) {
                    continue
                }

                val call = instructions[index + 1]
                val moveResult = instructions[index + 2]
                if (call.opcode != Opcode.INVOKE_VIRTUAL || moveResult.opcode != Opcode.MOVE_RESULT) continue

                val reference = (call as ReferenceInstruction).reference as? MethodReference ?: continue
                if (reference.definingClass != "Landroid/content/res/Resources;" || reference.name != "getBoolean") continue

                return BooleanResult(method, index + 2, (moveResult as OneRegisterInstruction).registerA)
            }
        }
    }

    return null
}
