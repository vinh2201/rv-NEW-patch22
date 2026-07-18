package app.revanced.patches.instagram.interaction.disableswipenavigation

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.AccessFlags

internal const val SWIPE_NAVIGATION_CONTAINER_CLASS_DESCRIPTOR =
    "Lcom/instagram/ui/swipenavigation/container/SwipeNavigationContainer;"
internal const val POSITION_CONFIG_CLASS_DESCRIPTOR =
    "Lcom/instagram/ui/swipenavigation/container/PositionConfig;"

// Re-asserts the nav ViewPager2's user-input flag on every touch.
internal val BytecodePatchContext.onInterceptTouchEventMethod by gettingFirstMethodDeclaratively {
    definingClass(SWIPE_NAVIGATION_CONTAINER_CLASS_DESCRIPTOR)
    name("onInterceptTouchEvent")
}

// Applies a new position to the container's edge-panel spring.
internal val BytecodePatchContext.setInternalPositionMethod by gettingFirstMethodDeclaratively {
    definingClass(SWIPE_NAVIGATION_CONTAINER_CLASS_DESCRIPTOR)
    name("setInternalPosition")
}

// Settles the edge swipe on release, feeding the fling velocity (2nd parameter) into the spring.
internal val BytecodePatchContext.swipeSettleMethod by gettingFirstMethodDeclaratively {
    definingClass(SWIPE_NAVIGATION_CONTAINER_CLASS_DESCRIPTOR)
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")
    parameterTypes("Landroid/view/MotionEvent;", "F", "J")
}
