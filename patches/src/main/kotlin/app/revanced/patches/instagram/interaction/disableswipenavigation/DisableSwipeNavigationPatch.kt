package app.revanced.patches.instagram.interaction.disableswipenavigation

import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.addInstructionsAtControlFlowLabel
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

@Suppress("unused")
val disableSwipeNavigationPatch = bytecodePatch(
    name = "Disable swipe navigation",
    description = "Disables swiping between the main navigation tabs and swiping to the camera. " +
        "Tapping the tabs still works.",
    use = false,
) {
    compatibleWith("com.instagram.android"("425.0.0.47.61"))

    apply {
        // The container re-asserts the nav pager's setUserInputEnabled on every touch, force it false.
        onInterceptTouchEventMethod.apply {
            val callIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL && methodReference?.name == "setUserInputEnabled"
            }
            // invoke-virtual { receiver, flag } — flag is the second (D) register.
            val flagRegister = getInstruction<FiveRegisterInstruction>(callIndex).registerD
            // The call is a branch target, relocate incoming labels onto the injection to cover every path.
            addInstructionsAtControlFlowLabel(callIndex, "const/4 v$flagRegister, 0x0")
        }

        // Freeze the edge-panel spring against gestures: drop both sources fed to setInternalPosition —
        // "swipe" (drag) and "tap_partially_visible_panel" (release snap). Programmatic opens differ.
        setInternalPositionMethod.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, p1
                iget-object v0, v0, $POSITION_CONFIG_CLASS_DESCRIPTOR->A0D:Ljava/lang/String;
                const-string v1, "tap_partially_visible_panel"
                invoke-virtual { v1, v0 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v1
                if-nez v1, :ig_swipe_block
                const-string v1, "swipe"
                invoke-virtual { v1, v0 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v1
                if-eqz v1, :ig_swipe_continue
                :ig_swipe_block
                return-void
            """,
            ExternalLabel("ig_swipe_continue", setInternalPositionMethod.getInstruction(0)),
        )

        // A hard fling feeds velocity (p2) straight into the spring, bypassing the block above, zero it.
        swipeSettleMethod.addInstructions(
            0,
            """
                const/4 v0, 0x0
                move/from16 p2, v0
            """,
        )
    }
}
