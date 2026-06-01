package app.revanced.patches.youtube.layout.sponsorblock

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patches.shared.misc.mapping.ResourceType
import app.revanced.patches.youtube.layout.player.overlay.createPlayerOverviewMethodMatch
import app.revanced.patches.youtube.misc.playercontrols.playerBottomGradientScrimMethodMatch
import app.revanced.patches.youtube.shared.getLayoutConstructorMethodMatch
import app.revanced.patches.youtube.shared.seekbarMethod
import app.revanced.util.getting
import app.revanced.util.using
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val BytecodePatchContext.appendTimeMethodMatch by composingFirstMethod {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")
    parameterTypes(
        "Ljava/lang/CharSequence;",
        "Ljava/lang/CharSequence;",
        "Ljava/lang/CharSequence;"
    )
    instructions(
        ResourceType.STRING("total_time"),
        method { toString() == "Landroid/content/res/Resources;->getString(I[Ljava/lang/Object;)Ljava/lang/String;" },
        after(Opcode.MOVE_RESULT_OBJECT()),
    )
}

/**
 * Matches same method as [createPlayerOverviewMethodMatch] and [playerBottomGradientScrimMethodMatch].
 */
internal val BytecodePatchContext.controlsOverlayMethodMatch by getting {
    firstMethodComposite {
        returnType("V")
        parameterTypes()
        instructions(
            ResourceType.ID.invoke("inset_overlay_view_layout"),
            afterAtMost(20, allOf(Opcode.CHECK_CAST(), type("Landroid/widget/FrameLayout;"))),
        )
    }
} using { getLayoutConstructorMethodMatch().immutableMethod }

internal val BytecodePatchContext.rectangleFieldInvalidatorMethodMatch by getting {
    firstMethodComposite {
        returnType("V")
        parameterTypes()
        instructions(method("invalidate"))
    }
} using { seekbarMethod }

internal val BytecodePatchContext.adProgressTextViewVisibilityMethodMatch by composingFirstMethod {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")
    parameterTypes("Z")
    instructions(
        method {
            name == "setVisibility" && definingClass ==
                    "Lcom/google/android/libraries/youtube/ads/player/ui/AdProgressTextView;"
        },
    )
}
