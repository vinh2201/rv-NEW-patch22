package app.revanced.patches.twitch.analytics

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.instructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.returnEarly

@Suppress("unused")
val disableAnalyticsPatch = bytecodePatch("Disable analytics") {
    compatibleWith("tv.twitch.android.app")

    apply {
        trackEventMethod.returnEarly()

        analyticsFeatureInitMethod.apply {
            val insertIndex = instructions.lastIndex

            addInstructions(
                insertIndex,
                """
                    const/4 v0, 0x0
                    iput-boolean v0, p0, Lcom/amazonaws/ivs/player/IVSFeature;->isEnabled:Z
                """,
            )
        }
    }
}
