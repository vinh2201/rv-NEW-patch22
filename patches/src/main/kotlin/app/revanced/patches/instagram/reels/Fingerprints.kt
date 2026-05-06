package app.revanced.patches.instagram.reels

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext
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
    instructions(method("setUserInputEnabled"))
}

context(_: BytecodePatchContext)
internal fun ClassDef.getClipsViewPagerImplReEnableScrollingMethod() = firstMethodDeclaratively {
    parameterTypes()
    returnType("V")
    instructions(1L(), method("setUserInputEnabled"))
}
