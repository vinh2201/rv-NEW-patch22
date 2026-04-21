package app.revanced.patches.zwanoo.speedtest.misc

import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.returnEarly

@Suppress("unused")
val hideUpgradePromptsPatch = bytecodePatch("Hide upgrade prompts") {
    compatibleWith("org.zwanoo.android.speedtest"("7.0.0", "7.0.3"))

    apply {
        showUpgradeDialogMethod.returnEarly()

        subscriptionExpiryMethod.returnEarly()
    }
}
