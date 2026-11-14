package app.revanced.patches.viber.layout.promotions

import app.revanced.patcher.gettingFirstImmutableMethodOrNull
import app.revanced.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** The tag is read at most this many instructions after the `move-result` of the flag. */
private const val CALLS_INTRO_TAG_DISTANCE = 9

/** The chat list fragment: it has both result keys of the channel and bot suggestions. */
internal val BytecodePatchContext.chatSuggestionsAnchor by gettingFirstImmutableMethodOrNull(
    "fragment_result_explore_bundle_key",
    "fragment_result_ess_key",
)

/** `<clinit>` of the Calls tab contacts fragment, with the Viber Out banner binding. */
internal val BytecodePatchContext.callsContactsBindingAnchor by gettingFirstImmutableMethodOrNull(
    "getViberOutBannerBinding()Lcom/viber/voip/databinding/CallsTabContactsViberOutBannerBinding;",
)

/** A boolean that is set to false: the register is written right after [index]. */
internal class BooleanValue(val method: Method, val index: Int, val register: Int)

private fun Instruction.isViewVisibilityCall(opcode: Opcode): Boolean {
    if (this.opcode != opcode) return false

    val reference = (this as ReferenceInstruction).reference as? MethodReference ?: return false
    return reference.returnType == "V" &&
        reference.parameterTypes.size == 2 &&
        reference.parameterTypes[0] == "Landroid/view/View;" &&
        reference.parameterTypes[1] == "Z"
}

private fun Instruction.isSetBoolean(): Boolean {
    val reference = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return reference.returnType == "V" && reference.parameterTypes.size == 1 && reference.parameterTypes[0] == "Z"
}

/** A method that gets a boolean parameter set to false, [register] is the parameter register. */
internal class ParameterOverride(val method: Method, val register: String)

private fun Method.sinkCalls(): Set<String> = implementation?.instructions
    ?.filter { it.opcode == Opcode.INVOKE_VIRTUAL && it.isSetBoolean() }
    ?.map { instruction ->
        val reference = (instruction as ReferenceInstruction).reference as MethodReference
        "${reference.definingClass}->${reference.name}"
    }
    ?.toSet()
    ?: emptySet()

/**
 * The methods that set the visibility of the "Popular channels & bots" suggestions of the chat list.
 * - Viber 26.7 and newer: one method, `void show(boolean visible, boolean other)`.
 * - Viber 23.x - 26.1: two methods of the same class, that set the visibility of the header adapter and of the
 *   suggestions adapter: `void setVisible(boolean)` and `void setSuggestions(List, boolean show)`.
 *   The second one decides the visibility again every time the suggestions are loaded, so both have to be patched.
 */
internal fun BytecodePatchContext.findChatSuggestionsVisibilityMethods(): List<ParameterOverride> {
    val anchor = chatSuggestionsAnchor ?: return emptyList()
    val chatListClass = classDefs.firstOrNull { it.type == anchor.definingClass } ?: return emptyList()

    val methods = chatListClass.methods.filter { it.returnType == "V" && !AccessFlags.STATIC.isSet(it.accessFlags) }
    fun Method.parameters() = parameterTypes.map { it.toString() }

    methods.singleOrNull { it.parameters() == listOf("Z", "Z") }?.let {
        return listOf(ParameterOverride(it, "p1"))
    }

    // Older versions: a short method that forwards the boolean to the header and suggestions adapters.
    val setVisible = methods.singleOrNull { method ->
        method.parameters() == listOf("Z") &&
            (method.implementation?.instructions?.count() ?: Int.MAX_VALUE) <= 8 &&
            method.implementation?.instructions?.count { it.opcode == Opcode.INVOKE_VIRTUAL && it.isSetBoolean() } == 2
    } ?: return emptyList()

    val result = mutableListOf(ParameterOverride(setVisible, "p1"))

    // ... and the method that loads the suggestions and decides if they are visible, using the same adapters.
    val adapters = setVisible.sinkCalls()
    methods.singleOrNull { method ->
        method.parameters() == listOf("Ljava/util/List;", "Z") && method.sinkCalls().containsAll(adapters)
    }?.let { result += ParameterOverride(it, "p2") }

    return result
}

/**
 * The visibility of the banner and shadow of the Viber Out banner of the Calls tab.
 * `onLoadFinished` reads two booleans and gives each one to `ViewUtils.setVisible(View, boolean)`.
 */
internal fun BytecodePatchContext.findCallsBannerVisibility(): List<BooleanValue> {
    val bindingClass = callsContactsBindingAnchor
        ?.takeIf { it.name == "<clinit>" }
        ?.let { anchor -> classDefs.firstOrNull { it.type == anchor.definingClass } }
        ?: return emptyList()

    val onLoadFinished = bindingClass.methods.firstOrNull { it.name == "onLoadFinished" && it.returnType == "V" }
        ?: return emptyList()
    val instructions = onLoadFinished.implementation?.instructions?.toList() ?: return emptyList()

    return (0 until instructions.size - 1)
        .filter { index ->
            instructions[index].opcode == Opcode.IGET_BOOLEAN &&
                instructions[index + 1].isViewVisibilityCall(Opcode.INVOKE_STATIC)
        }
        .map { index -> BooleanValue(onLoadFinished, index, (instructions[index] as OneRegisterInstruction).registerA) }
}

/**
 * The visibility of the Viber Out section of the Calls tab:
 * ```
 * const/4 v0, 0x0
 * iget-boolean v0, ..., Z
 * xor-int/2addr v0, v1
 * invoke-virtual { v?, v0 }, View->setVisibility...(View, boolean)V
 * ```
 * Returns the register of the result of the `xor-int/2addr`.
 */
internal fun BytecodePatchContext.findViberOutSectionVisibility(): BooleanValue? {
    val bindingClass = callsContactsBindingAnchor
        ?.takeIf { it.name == "<clinit>" }
        ?.let { anchor -> classDefs.firstOrNull { it.type == anchor.definingClass } }
        ?: return null

    val onLoadFinished = bindingClass.methods.firstOrNull { it.name == "onLoadFinished" && it.returnType == "V" } ?: return null
    val instructions = onLoadFinished.implementation?.instructions?.toList() ?: return null

    for (index in 0 until instructions.size - 3) {
        if (instructions[index].opcode == Opcode.CONST_4 &&
            instructions[index + 1].opcode == Opcode.IGET_BOOLEAN &&
            instructions[index + 2].opcode == Opcode.XOR_INT_2ADDR &&
            instructions[index + 3].isViewVisibilityCall(Opcode.INVOKE_VIRTUAL)
        ) {
            return BooleanValue(onLoadFinished, index + 2, (instructions[index + 2] as OneRegisterInstruction).registerA)
        }
    }

    return null
}

/**
 * The flag that shows the Calls tab introduction:
 * ```
 * invoke-virtual { v? }, FeatureSwitch->isEnabled()Z
 * move-result v0
 * ... const-string "FTUX_CALLS_TAB_FRAGMENT_TAG"
 * ```
 */
internal fun BytecodePatchContext.findCallsIntroFlag(): BooleanValue? {
    for (classDef in classDefs) {
        for (method in classDef.methods) {
            val instructions = method.implementation?.instructions?.toList() ?: continue

            for (index in 0 until instructions.size - 1) {
                val call = instructions[index]
                if (call.opcode != Opcode.INVOKE_VIRTUAL && call.opcode != Opcode.INVOKE_INTERFACE) continue

                val reference = (call as ReferenceInstruction).reference as? MethodReference ?: continue
                if (reference.name != "isEnabled" || reference.returnType != "Z") continue

                val moveResult = instructions[index + 1]
                if (moveResult.opcode != Opcode.MOVE_RESULT) continue

                val tagInRange = (index + 2..minOf(instructions.size - 1, index + 1 + CALLS_INTRO_TAG_DISTANCE)).any {
                    ((instructions[it] as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                        "FTUX_CALLS_TAB_FRAGMENT_TAG"
                }
                if (!tagInRange) continue

                return BooleanValue(method, index + 1, (moveResult as OneRegisterInstruction).registerA)
            }
        }
    }

    return null
}
