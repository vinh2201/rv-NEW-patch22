package app.revanced.patches.zwanoo.speedtest.misc

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.returnEarly

@Suppress("unused")
val hideUpgradePromptsPatch = bytecodePatch("Hide upgrade prompts") {
    compatibleWith("org.zwanoo.android.speedtest"("7.0.0", "7.0.3"))

    apply {
        showUpgradeDialogMethod.returnEarly()

        // Return null for the expiry date, indicating the subscription never expires
        subscriptionExpiryMethod.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return-object v0
            """,
        )
    }
}
