
package app.revanced.patches.instagram.misc.removeBuildExpiredPopup

import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType

private const val MILLISECOND_IN_A_DAY_LITERAL = 0x5265c00L

// The expired build lockout screen logs its source location through this string.
// It uniquely identifies the method that computes the build age (current time
// minus build timestamp, divided by one day) which is then truncated to an int
// number of days by the long-to-int the patch overrides.
internal val BytecodePatchContext.appUpdateLockoutBuilderMethod by gettingFirstMethodDeclaratively(
    "com.instagram.release.lockout.ExpiredLockoutScreen (LockoutFragment.kt:206)",
) {
    returnType("V")
    parameterTypes("Landroidx/fragment/app/FragmentActivity;", "LX/748;")
}
