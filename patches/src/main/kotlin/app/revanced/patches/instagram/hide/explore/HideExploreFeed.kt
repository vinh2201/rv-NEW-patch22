package app.revanced.patches.instagram.hide.explore

import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.indexOfFirstInstructionOrThrow
import app.revanced.util.indexOfFirstLiteralInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val SECTIONAL_ITEMS_KEY = 3151L

@Suppress("unused")
val hideExploreFeedPatch = bytecodePatch(
    name = "Hide explore feed",
    description = "Hides posts and reels from the explore/search page.",
    use = false,
) {
    compatibleWith("com.instagram.android")

    apply {
        exploreResponseJsonParserMethodMatch.method.apply {
            val sectionalItemsKeyIndex = indexOfFirstLiteralInstructionOrThrow(SECTIONAL_ITEMS_KEY)
            val targetStringIndex = indexOfFirstInstructionOrThrow(
                sectionalItemsKeyIndex + 1,
                Opcode.MOVE_RESULT_OBJECT,
            )
            val targetStringRegister = getInstruction<OneRegisterInstruction>(targetStringIndex).registerA

            replaceInstruction(targetStringIndex, "const-string v$targetStringRegister, \"BOGUS\"")
        }
    }
}
