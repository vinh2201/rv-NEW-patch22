package app.revanced.patches.instagram.ads

import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.returnEarly

@Suppress("unused")
val hideAdsPatch = bytecodePatch("Hide ads") {
    compatibleWith("com.instagram.android")

    apply {
        val adInjectorMethod = isAdPodAdInjectorMethodOrNull
            ?: insertItemAdInjectorMethodOrNull
            ?: throw PatchException("Could not find the ad injector method.")

        adInjectorMethod.returnEarly(false)
    }
}
