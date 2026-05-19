package app.revanced.patches.all.misc.transformation

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.extensions.replaceInstruction
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

typealias Instruction35cInfo = Triple<IMethodCall, Instruction35c, Int>
typealias Instruction3rcInfo = Triple<IMethodCall, Instruction3rc, Int>
typealias InstructionTransform = (MutableMethod) -> Unit

interface IMethodCall {
    val definedClassName: String
    val methodName: String
    val methodParams: Array<String>
    val returnType: String

    /**
     * Replaces an invoke-virtual instruction with an invoke-static instruction,
     * which calls a static replacement method in the respective extension class.
     * The method definition in the extension class is expected to be the same,
     * except that the method should be static and take as a first parameter
     * an instance of the class, in which the original method was defined in.
     *
     * Example:
     *
     * original method: Window#setFlags(int, int)
     *
     * replacement method: Extension#setFlags(Window, int, int)
     */
    fun replaceInvokeVirtualWithExtension(
        definingClassDescriptor: String,
        method: MutableMethod,
        instruction: Instruction35c,
        instructionIndex: Int,
    ) {
        val args = with(instruction) {
            arrayOf(registerC, registerD, registerE, registerF, registerG)
                .take(registerCount).joinToString(", ") { "v$it" }
        }
        val replacementMethod =
            "$methodName(${definedClassName}${methodParams.joinToString(separator = "")})$returnType"

        method.replaceInstruction(
            instructionIndex,
            "invoke-static { $args }, $definingClassDescriptor->$replacementMethod",
        )
    }

    /**
     * Replaces an invoke-virtual/range instruction with invoke-static/range.
     * The register range is unchanged because the static replacement also receives
     * the original receiver as its first argument.
     */
    fun replaceInvokeVirtualRangeWithExtension(
        definingClassDescriptor: String,
        method: MutableMethod,
        instruction: Instruction3rc,
        instructionIndex: Int,
    ) {
        val start = instruction.startRegister
        val end = start + instruction.registerCount - 1
        val replacementMethod =
            "$methodName(${definedClassName}${methodParams.joinToString(separator = "")})$returnType"

        method.replaceInstruction(
            instructionIndex,
            "invoke-static/range { v$start .. v$end }, $definingClassDescriptor->$replacementMethod",
        )
    }
}

inline fun <reified E> fromMethodReference(
    methodReference: MethodReference,
)
        where E : Enum<E>, E : IMethodCall = enumValues<E>().firstOrNull { search ->
    search.definedClassName == methodReference.definingClass &&
        search.methodName == methodReference.name &&
        methodReference.parameterTypes.toTypedArray().contentEquals(search.methodParams) &&
        search.returnType == methodReference.returnType
}

inline fun <reified E> filterMapInstruction35c(
    extensionClassDescriptorPrefix: String,
    classDef: ClassDef,
    instruction: Instruction,
    instructionIndex: Int,
): Instruction35cInfo? where E : Enum<E>, E : IMethodCall {
    if (classDef.startsWith(extensionClassDescriptorPrefix)) {
        // avoid infinite recursion
        return null
    }

    if (instruction.opcode != Opcode.INVOKE_VIRTUAL) {
        return null
    }

    val invokeInstruction = instruction as Instruction35c
    val methodRef = invokeInstruction.reference as MethodReference
    val methodCall = fromMethodReference<E>(methodRef) ?: return null

    return Instruction35cInfo(methodCall, invokeInstruction, instructionIndex)
}

/**
 * Combined filter for both [filterMapInstruction35c] (INVOKE_VIRTUAL) and
 * [filterMapInstruction3rc] (INVOKE_VIRTUAL_RANGE) in a single instruction scan.
 * Returns a pre-bound [InstructionTransform] so the caller's transform lambda is trivial:
 * `transform = { method, handler -> handler(method) }`.
 */
inline fun <reified E> filterMapInstructionVirtual(
    extensionClassDescriptor: String,
    extensionClassDescriptorPrefix: String,
    classDef: ClassDef,
    instruction: Instruction,
    instructionIndex: Int,
): InstructionTransform? where E : Enum<E>, E : IMethodCall {
    filterMapInstruction35c<E>(extensionClassDescriptorPrefix, classDef, instruction, instructionIndex)
        ?.let { (call, instr, idx) ->
            return { method ->
                call.replaceInvokeVirtualWithExtension(extensionClassDescriptor, method, instr, idx)
            }
        }
    filterMapInstruction3rc<E>(extensionClassDescriptorPrefix, classDef, instruction, instructionIndex)
        ?.let { (call, instr, idx) ->
            return { method ->
                call.replaceInvokeVirtualRangeWithExtension(extensionClassDescriptor, method, instr, idx)
            }
        }
    return null
}

/**
 * Sibling of [filterMapInstruction35c] for the range-invoke form (INVOKE_VIRTUAL_RANGE / 3rc).
 * Needed when the target app's compiler emits range invokes due to register pressure.
 * Use [filterMapInstructionVirtual] to match both forms in one pass.
 */
inline fun <reified E> filterMapInstruction3rc(
    extensionClassDescriptorPrefix: String,
    classDef: ClassDef,
    instruction: Instruction,
    instructionIndex: Int,
): Instruction3rcInfo? where E : Enum<E>, E : IMethodCall {
    if (classDef.startsWith(extensionClassDescriptorPrefix)) {
        return null
    }

    if (instruction.opcode != Opcode.INVOKE_VIRTUAL_RANGE) {
        return null
    }

    val invokeInstruction = instruction as Instruction3rc
    val methodRef = invokeInstruction.reference as MethodReference
    val methodCall = fromMethodReference<E>(methodRef) ?: return null

    return Instruction3rcInfo(methodCall, invokeInstruction, instructionIndex)
}
