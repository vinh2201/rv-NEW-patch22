package app.revanced.patches.viber.layout.ai

import app.revanced.patcher.gettingFirstImmutableMethodOrNull
import app.revanced.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val CHATGPT_PROVIDER_STRING =
    "getEntryPointProvider()Lcom/viber/voip/feature/search/data/experiments/SearchChatGptEntryPointExperimentsProvider;"
private const val TRANSLATE_PROVIDER_STRING =
    "getKeyboardLanguagesProvider()Lcom/viber/voip/feature/translate/message/domain/TranslateKeyboardLanguagesProvider;"
private const val NEWS_BRIEF_STRING =
    "getBannerSettingsRepository()Lcom/viber/voip/feature/ai/news/brief/domain/BannerSettingsRepository;"
private const val IMAGE_REMIX_DIALOG_STRING = "image_remix_ftue_dialog"
private const val IMAGE_REMIX_PRESETS_STRING = "com.viber.voip.custom_cam_media_preview.show_image_remix_presets"

/** Instructions that can be between the availability of the ChatGPT settings and its title. */
private const val CHATGPT_SETTINGS_ROW_DISTANCE = 3

/** Instructions between the availability of inline translate and the null check of its view tag. */
private const val INLINE_TRANSLATE_TAG_DISTANCE = 2

internal val BytecodePatchContext.chatGptEntryPointAnchor by gettingFirstImmutableMethodOrNull(CHATGPT_PROVIDER_STRING)
internal val BytecodePatchContext.inlineTranslateAnchor by gettingFirstImmutableMethodOrNull(TRANSLATE_PROVIDER_STRING)
internal val BytecodePatchContext.newsBriefAnchor by gettingFirstImmutableMethodOrNull(NEWS_BRIEF_STRING)

/** A boolean result (`move-result`) that has to be replaced. */
internal class BooleanResult(val method: Method, val moveResultIndex: Int, val register: Int)

private fun Instruction.isCall() = opcode.name.startsWith("INVOKE")

private fun Instruction.methodReference() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.stringConstant() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun Method.callsBooleanValue() = implementation?.instructions?.any {
    val reference = it.methodReference()
    it.isCall() && reference?.definingClass == "Ljava/lang/Boolean;" && reference.name == "booleanValue"
} == true

/** Viber 29.x: the availability of the ChatGPT entry point of the search. */
internal fun BytecodePatchContext.findChatGptEntryAvailability(): Method? {
    val anchor = chatGptEntryPointAnchor ?: return null
    val providerClass = classDefs.firstOrNull { it.type == anchor.definingClass } ?: return null

    return providerClass.methods.singleOrNull { it.returnType == "Z" && it.callsBooleanValue() }
}

/**
 * Viber 29.x: the availability of the ChatGPT row of the settings:
 * ```
 * invoke-virtual { v?, v? }, Feature->isAvailable(Z)Z
 * move-result v0
 * if-eqz v0, :skip
 * new-instance v0, SettingsRow
 * const v0, 0x7f1417bb   # the string resource of the preference category
 * ```
 */
internal fun BytecodePatchContext.findChatGptSettingsRow(resourceId: Long): BooleanResult? {
    for (classDef in classDefs) {
        for (method in classDef.methods) {
            val instructions = method.implementation?.instructions?.toList() ?: continue

            for (index in 0 until instructions.size - 1) {
                val call = instructions[index]
                val reference = call.methodReference()
                if (!call.isCall() || reference == null || reference.returnType != "Z") continue
                if (reference.parameterTypes.size != 1 || reference.parameterTypes[0] != "Z") continue

                val moveResult = instructions[index + 1]
                if (moveResult.opcode != Opcode.MOVE_RESULT) continue

                val hasResource = (index + 2..minOf(instructions.size - 1, index + 1 + CHATGPT_SETTINGS_ROW_DISTANCE)).any {
                    (instructions[it] as? NarrowLiteralInstruction)?.narrowLiteral == resourceId.toInt()
                }
                if (hasResource) return BooleanResult(method, index + 1, (moveResult as OneRegisterInstruction).registerA)
            }
        }
    }

    return null
}

/**
 * Viber 29.x: the visibility of the inline translate view:
 * ```
 * invoke-virtual { v?, v?, v? }, Feature->isEnabled(Object, Object)Z
 * move-result v8
 * iget-object v?, v?, Binder->view:Landroid/view/View;
 * invoke-virtual { v? }, View->getTag()Object
 * ```
 */
internal fun BytecodePatchContext.findInlineTranslateVisibility(): BooleanResult? {
    val anchor = inlineTranslateAnchor ?: return null
    val binderClass = classDefs.firstOrNull { it.type == anchor.definingClass } ?: return null

    for (method in binderClass.methods) {
        val instructions = method.implementation?.instructions?.toList() ?: continue

        for (index in 0 until instructions.size - 3) {
            val call = instructions[index]
            val reference = call.methodReference()
            if (call.opcode != Opcode.INVOKE_VIRTUAL && call.opcode != Opcode.INVOKE_VIRTUAL_RANGE) continue
            if (reference == null || reference.returnType != "Z" || reference.parameterTypes.size != 2) continue
            if (!reference.parameterTypes[0].startsWith("L") || !reference.parameterTypes[1].startsWith("L")) continue

            val moveResult = instructions[index + 1]
            val viewRead = instructions[index + 2]
            if (moveResult.opcode != Opcode.MOVE_RESULT || viewRead.opcode != Opcode.IGET_OBJECT) continue
            if (((viewRead as ReferenceInstruction).reference as? FieldReference)?.type != "Landroid/view/View;") continue

            val readsTag = (index + 3..minOf(instructions.size - 1, index + 2 + INLINE_TRANSLATE_TAG_DISTANCE)).any {
                val tagCall = instructions[it].methodReference()
                instructions[it].isCall() && tagCall?.definingClass == "Landroid/view/View;" && tagCall.name == "getTag"
            }
            if (readsTag) return BooleanResult(method, index + 1, (moveResult as OneRegisterInstruction).registerA)
        }
    }

    return null
}

/** Viber 29.x: `void show(Context, FragmentManager, X, Y)` that shows the "image remix" introduction. */
internal fun BytecodePatchContext.findImageRemixIntroduction(): Method? {
    for (classDef in classDefs) {
        for (method in classDef.methods) {
            val parameters = method.parameterTypes
            if (method.returnType != "V" || parameters.size != 4 || parameters[0] != "Landroid/content/Context;") continue

            if (method.implementation?.instructions?.any { it.stringConstant() == IMAGE_REMIX_DIALOG_STRING } == true) {
                return method
            }
        }
    }

    return null
}

/** Viber 29.x: the "show image remix presets" extra read from the intent of the media preview. */
internal fun BytecodePatchContext.findImageRemixPresets(): BooleanResult? {
    for (classDef in classDefs) {
        for (method in classDef.methods) {
            if (method.returnType != "V") continue

            val instructions = method.implementation?.instructions?.toList() ?: continue
            val stringIndex = instructions.indexOfFirst { it.stringConstant() == IMAGE_REMIX_PRESETS_STRING }
            if (stringIndex < 0) continue

            for (index in stringIndex + 1 until instructions.size - 1) {
                val reference = instructions[index].methodReference()
                if (instructions[index].isCall() &&
                    reference?.definingClass == "Landroid/os/BaseBundle;" &&
                    reference.name == "getBoolean" &&
                    reference.parameterTypes.size == 2 &&
                    reference.returnType == "Z" &&
                    instructions[index + 1].opcode == Opcode.MOVE_RESULT
                ) {
                    return BooleanResult(method, index + 1, (instructions[index + 1] as OneRegisterInstruction).registerA)
                }
            }
        }
    }

    return null
}

/** Viber 29.x: `Object isEligible(ContinuationImpl)` of the news brief banner (a coroutine). */
internal fun BytecodePatchContext.findNewsBriefEligibility(): Method? {
    val anchor = newsBriefAnchor?.takeIf { it.name == "<clinit>" } ?: return null
    val bannerClass = classDefs.firstOrNull { it.type == anchor.definingClass } ?: return null

    return bannerClass.methods.singleOrNull {
        it.returnType == "Ljava/lang/Object;" &&
            it.parameterTypes.size == 1 &&
            it.parameterTypes[0] == "Lkotlin/coroutines/jvm/internal/ContinuationImpl;"
    }
}

/**
 * The constructor of a preference with a key and a default value:
 * ```
 * const-string v1, "display_chat_summary_feature_key"
 * invoke-direct { v0, v1, v2 }, BooleanPreference-><init>(String, boolean)V
 * ```
 * The preference is "redirected": it reads a key that does not exist, with the default that disables the feature.
 */
internal class PreferenceSite(
    val method: Method,
    val callIndex: Int,
    val key: String,
    val callOpcode: Opcode,
    val receiverRegister: Int,
    val call: MethodReference,
    /** Registers that can be written before the call, because their value is not used after it. */
    val freeRegisters: List<Int>,
)

private val PREFERENCE_CALL_OPCODES = setOf(Opcode.INVOKE_DIRECT, Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_INTERFACE)

/** The preference is created at most this many instructions after its key. */
private const val PREFERENCE_CALL_DISTANCE = 2

private val PURE_WRITES = setOf(
    Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16, Opcode.CONST_STRING,
    Opcode.CONST_STRING_JUMBO, Opcode.CONST_CLASS, Opcode.NEW_INSTANCE, Opcode.MOVE_RESULT,
    Opcode.MOVE_RESULT_OBJECT, Opcode.SGET, Opcode.SGET_OBJECT, Opcode.SGET_BOOLEAN,
)

private fun Instruction.registers(): Set<Int> {
    val result = mutableSetOf<Int>()

    if (this is OneRegisterInstruction) result += registerA
    if (this is TwoRegisterInstruction) result += registerB
    if (this is ThreeRegisterInstruction) result += registerC
    if (this is FiveRegisterInstruction) {
        result += listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    }
    if (this is RegisterRangeInstruction) {
        for (register in startRegister until startRegister + registerCount) result += register
    }

    // A wide value uses a pair of registers.
    if (opcode.name.contains("WIDE") || opcode.name.contains("LONG") || opcode.name.contains("DOUBLE")) {
        result += result.map { it + 1 }
    }

    return result
}

/**
 * True if the value of [register] is not read after [callIndex]: the first instruction that uses it only writes it,
 * or the method returns before using it. Branches are not followed.
 */
private fun isDeadAfter(instructions: List<Instruction>, callIndex: Int, register: Int): Boolean {
    for (index in callIndex + 1 until instructions.size) {
        val instruction = instructions[index]

        if (register in instruction.registers()) {
            return instruction.opcode in PURE_WRITES && (instruction as OneRegisterInstruction).registerA == register
        }

        val name = instruction.opcode.name
        if (name.startsWith("RETURN")) return true
        if (name.startsWith("IF_") || name.startsWith("GOTO") || name.contains("SWITCH") || name == "THROW") return false
    }

    return true
}

/**
 * Finds the preferences of [keys].
 * Scratch registers are needed to give the preference another key and default without changing the registers
 * of the method: they are searched among the registers that are not read after the call.
 */
internal fun BytecodePatchContext.findPreferenceSites(keys: Set<String>): List<PreferenceSite> {
    val sites = mutableListOf<PreferenceSite>()

    for (classDef in classDefs) {
        for (method in classDef.methods) {
            val implementation = method.implementation ?: continue
            val instructions = implementation.instructions.toList()

            for (index in instructions.indices) {
                val key = instructions[index].stringConstant() ?: continue
                if (key !in keys) continue

                val keyRegister = (instructions[index] as OneRegisterInstruction).registerA

                for (callIndex in index + 1..minOf(instructions.size - 1, index + PREFERENCE_CALL_DISTANCE)) {
                    val call = instructions[callIndex]
                    val reference = call.methodReference()
                    if (call.opcode !in PREFERENCE_CALL_OPCODES || reference == null) continue
                    if (reference.parameterTypes.size != 2 ||
                        reference.parameterTypes[0] != "Ljava/lang/String;" ||
                        reference.parameterTypes[1] != "Z"
                    ) {
                        continue
                    }

                    val registers = call as FiveRegisterInstruction
                    if (registers.registerCount != 3 || registers.registerD != keyRegister) continue

                    val operands = setOf(registers.registerC, registers.registerD, registers.registerE)
                    val free = (0..15).filter { register ->
                        register < implementation.registerCount &&
                            register !in operands &&
                            implementation.tryBlocks.isEmpty() &&
                            isDeadAfter(instructions, callIndex, register)
                    }

                    sites += PreferenceSite(method, callIndex, key, call.opcode, registers.registerC, reference, free)
                    break
                }
            }
        }
    }

    return sites
}
