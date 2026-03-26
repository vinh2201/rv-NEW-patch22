package app.revanced.patches.youtube.layout.player.overlay

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patches.shared.misc.mapping.ResourceType
import app.revanced.patches.youtube.layout.sponsorblock.controlsOverlayMethodMatch
import app.revanced.patches.youtube.misc.playercontrols.playerBottomGradientScrimMethodMatch
import com.android.tools.smali.dexlib2.Opcode

/**
 * Matches same method as [controlsOverlayMethodMatch] and [playerBottomGradientScrimMethodMatch].
 */
internal val BytecodePatchContext.createPlayerOverviewMethodMatch by composingFirstMethod {
    returnType("V")
    instructions(
        ResourceType.ID("scrim_overlay"),
        afterAtMost(10, allOf(Opcode.CHECK_CAST(), type("Landroid/widget/ImageView;"))),
    )
}
