package app.revanced.patches.instagram.reels

import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType

internal val BytecodePatchContext.clipsViewPagerImplGetViewAtIndexMethod by gettingFirstMethodDeclaratively("ClipsViewPagerImpl_getViewAtIndex")

internal val BytecodePatchContext.clipsSwipeRefreshLayoutOnInterceptTouchEventMethod by gettingFirstMethodDeclaratively {
    parameterTypes("Landroid/view/MotionEvent;")
    definingClass("Linstagram/features/clips/viewer/ui/ClipsSwipeRefreshLayout;")
}

internal val BytecodePatchContext.clipsSwipeDirectionControllerInterceptMethod by gettingFirstMethodDeclaratively {
    definingClass("LX/JgN;")
    parameterTypes("Landroid/view/MotionEvent;", "Landroidx/recyclerview/widget/RecyclerView;")
    returnType("Z")
}

internal val BytecodePatchContext.clipsSwipeDirectionControllerResetMethod by gettingFirstMethodDeclaratively {
    definingClass("LX/JgN;")
    parameterTypes("Z")
    returnType("V")
}
