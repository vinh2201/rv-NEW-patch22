package app.revanced.patches.viber.misc.updates

import app.revanced.patcher.gettingFirstImmutableMethodOrNull
import app.revanced.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val UPDATE_DIALOG_STRING = "In App Update Dialog Displayed"

/**
 * `toString()` of the update prompt configuration: "UpdatePromptConfig(... versionDays: ...)".
 * Present in Viber 23.x, 26.x and 29.x.
 */
internal val BytecodePatchContext.updatePromptConfigToStringMethod by gettingFirstImmutableMethodOrNull("versionDays:")

internal class UpdatePromptCheck(val method: Method, val booleanReads: List<Pair<Int, Int>>)

/**
 * Finds the method that shows the update dialog. It reads two boolean fields of the update prompt
 * configuration (the timed check and the update link check) before logging "In App Update Dialog Displayed".
 * Returns the index and register of each one of the two reads.
 */
internal fun BytecodePatchContext.findUpdatePromptCheck(): UpdatePromptCheck? {
    val configType = updatePromptConfigToStringMethod?.definingClass ?: return null

    for (classDef in classDefs) {
        for (method in classDef.methods) {
            val instructions = method.implementation?.instructions?.toList() ?: continue

            val dialogIndex = instructions.indexOfFirst {
                it is ReferenceInstruction && (it.reference as? StringReference)?.string == UPDATE_DIALOG_STRING
            }
            if (dialogIndex < 0) continue

            val reads = (0 until dialogIndex).filter { index ->
                val instruction = instructions[index]
                if (instruction.opcode != Opcode.IGET_BOOLEAN) return@filter false

                val field = (instruction as ReferenceInstruction).reference as? FieldReference
                field != null && field.definingClass == configType && field.type == "Z"
            }
            if (reads.size < 2) continue

            return UpdatePromptCheck(
                method,
                reads.take(2).map { index -> index to (instructions[index] as OneRegisterInstruction).registerA },
            )
        }
    }

    return null
}
