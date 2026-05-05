package app.revanced.patches.instagram.reels

import app.revanced.patcher.classDef
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.getReference
import app.revanced.util.returnEarly
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Suppress("unused")
val disableReelsScrollingPatch = bytecodePatch(
    name = "Disable Reels scrolling",
    description = "Disables the endless scrolling behavior in Instagram Reels, preventing swiping to the next Reel. " +
        "Note: On a clean install, the 'Tip' animation may appear but will stop on its own after a few seconds.",
    use = false
) {
    compatibleWith("com.instagram.android")

    apply {
        val viewPagerField = clipsViewPagerImplGetViewAtIndexMethod.classDef.fields.first {
            it.type == "Landroidx/viewpager2/widget/ViewPager2;"
        }

        // Disable user input on the ViewPager2 to prevent scrolling.
        clipsViewPagerImplGetViewAtIndexMethod.addInstructions(
            0,
            """
               iget-object v0, p0, $viewPagerField
               const/4 v1, 0x0
               invoke-virtual { v0, v1 }, Landroidx/viewpager2/widget/ViewPager2;->setUserInputEnabled(Z)V
            """,
        )

        // Newer builds re-enable horizontal swipe input through the shared swipe
        // direction controller and helper methods on ClipsViewPagerImpl. Neutralize both.
        clipsSwipeDirectionControllerInterceptMethod.returnEarly(false)
        clipsSwipeDirectionControllerResetMethod.returnEarly()
        clipsViewPagerImplGetViewAtIndexMethod.classDef.methods
            .filter { method ->
                method.parameterTypes.isEmpty() &&
                    method.returnType == "V" &&
                    method.implementation?.instructions?.any { instruction ->
                        instruction.getReference<MethodReference>()?.definingClass == "Landroidx/viewpager2/widget/ViewPager2;" &&
                            instruction.getReference<MethodReference>()?.name == "setUserInputEnabled"
                    } == true
            }
            .forEach { it.returnEarly() }

        // Return false in onInterceptTouchEvent to disable pull-to-refresh.
        clipsSwipeRefreshLayoutOnInterceptTouchEventMethod.returnEarly(false)
    }
}
