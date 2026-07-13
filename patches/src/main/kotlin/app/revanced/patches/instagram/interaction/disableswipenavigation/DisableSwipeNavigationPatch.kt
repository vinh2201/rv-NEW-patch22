package app.revanced.patches.instagram.interaction.disableswipenavigation

import app.revanced.patcher.accessFlags
import app.revanced.patcher.custom
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.firstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import app.revanced.util.addInstructionsAtControlFlowLabel
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

private const val SWIPE_NAVIGATION_CONTAINER =
    "Lcom/instagram/ui/swipenavigation/container/SwipeNavigationContainer;"
private const val POSITION_CONFIG =
    "Lcom/instagram/ui/swipenavigation/container/PositionConfig;"

@Suppress("unused")
val disableSwipeNavigationPatch = bytecodePatch(
    name = "Disable swipe navigation",
    description = "Disables swiping between the main navigation tabs and swiping to the camera. " +
        "Tapping the tabs still works.",
    use = false,
) {
    compatibleWith("com.instagram.android"("425.0.0.47.61"))

    apply {
        // Tabs are a ViewPager2, the swipe is its own scroll, gated by ViewPager2.isUserInputEnabled(),
        // container re-asserts that flag on every touch via setUserInputEnabled() on its own pager,
        // so forcing that call's argument to false
        firstMethodDeclaratively {
            definingClass(SWIPE_NAVIGATION_CONTAINER)
            name("onInterceptTouchEvent")
        }.apply {
            val callIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL &&
                    methodReference?.name == "setUserInputEnabled"
            }
            // invoke-virtual { receiver, flag } — flag is the second (D) register.
            val flagRegister = getInstruction<FiveRegisterInstruction>(callIndex).registerD
            // The call is a branch target, so relocate incoming labels onto the injection - forces the
            // flag false on every path, not just the fall-through.
            addInstructionsAtControlFlowLabel(callIndex, "const/4 v$flagRegister, 0x0")
        }

        // The camera/direct edge swipe is separate: the container's own spring, driven through
        // setInternalPosition(config), where the gesture source is a string in field A0D. Dragging uses
        // "swipe"; releasing a partially-visible panel snaps it open with "tap_partially_visible_panel"
        // (that snap alone can reveal/warm up the camera). Drop both gesture sources to freeze the edge;
        // programmatic opens (e.g. the camera button) go through a separate fragment path, not this.
        // Match by name — the sibling setEndPanelExtraParameter shares the private void PositionConfig sig.
        firstMethodDeclaratively {
            definingClass(SWIPE_NAVIGATION_CONTAINER)
            name("setInternalPosition")
        }.apply {
            addInstructionsWithLabels(
                0,
                """
                    move-object/from16 v0, p1
                    iget-object v0, v0, $POSITION_CONFIG->A0D:Ljava/lang/String;
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
                ExternalLabel("ig_swipe_continue", getInstruction(0)),
            )
        }

        // A hard fling never reaches setInternalPosition: the container's velocity handler pushes the
        // fling velocity straight into the position spring (setVelocity), whose physics then carries the
        // panel open. Its float velocity is the 2nd parameter; zero it to kill the fling.
        firstMethodDeclaratively {
            definingClass(SWIPE_NAVIGATION_CONTAINER)
            accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
            returnType("V")
            custom {
                parameterTypes.map { it.toString() } ==
                    listOf("Landroid/view/MotionEvent;", "F", "J")
            }
        }.apply {
            // The velocity param sits in a high register (large .registers), so zero it via a low temp.
            addInstructions(
                0,
                """
                    const/4 v0, 0x0
                    move/from16 p2, v0
                """,
            )
        }
    }
}
