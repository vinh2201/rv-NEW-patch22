package app.revanced.patches.instagram.reels

import app.revanced.patcher.definingClass
import app.revanced.patcher.firstMethodDeclaratively
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.instructions
import app.revanced.patcher.method
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import app.revanced.util.literal
import com.android.tools.smali.dexlib2.iface.ClassDef

internal val BytecodePatchContext.clipsViewPagerImplGetViewAtIndexMethod by gettingFirstMethodDeclaratively("ClipsViewPagerImpl_getViewAtIndex")

internal val BytecodePatchContext.clipsSwipeRefreshLayoutOnInterceptTouchEventMethod by gettingFirstMethodDeclaratively {
    parameterTypes("Landroid/view/MotionEvent;")
    definingClass("Linstagram/features/clips/viewer/ui/ClipsSwipeRefreshLayout;")
}

internal val BytecodePatchContext.clipsSwipeDirectionControllerInterceptMethod by gettingFirstMethodDeclaratively {
    parameterTypes("Landroid/view/MotionEvent;", "Landroidx/recyclerview/widget/RecyclerView;")
    returnType("Z")
    instructions(method { toString() == "Ljava/lang/Math;->atan2(DD)D" })
}

internal val BytecodePatchContext.clipsSwipeDirectionControllerResetMethod by gettingFirstMethodDeclaratively {
    parameterTypes("Z")
    returnType("V")
    instructions(method { toString() == "Landroidx/viewpager2/widget/ViewPager2;->setUserInputEnabled(Z)V" })
}

context(_: BytecodePatchContext)
internal fun ClassDef.getClipsViewPagerImplReEnableScrollingMethod() = firstMethodDeclaratively {
    parameterTypes()
    returnType("V")
    instructions(method { toString() == "Landroidx/viewpager2/widget/ViewPager2;->setUserInputEnabled(Z)V" })
    literal { 1L }
}
