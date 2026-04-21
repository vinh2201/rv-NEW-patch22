package app.revanced.patches.twitch.ad.embedded

import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.twitch.ad.video.blockVideoAdsPatch

@Suppress("unused")
val blockEmbeddedAdsPatch = bytecodePatch(
    name = "Block embedded ads",
    description = "Resolves the v29 URL-builder lambda fingerprint. " +
        "Active interception is currently disabled - the OkHttpClient builder " +
        "anchor was inlined by R8 and a replacement hook is still pending.",
) {
    dependsOn(blockVideoAdsPatch)

    compatibleWith("tv.twitch.android.app"("16.9.1", "25.3.0", "29.0.3"))

    apply {
        // resolve the URL builder lambda so we get a clear "fingerprint did not match"
        // error if a future twitch version moves the strings, instead of silently
        // pretending the patch is healthy. We do not yet modify the method body.
        @Suppress("UNUSED_VARIABLE")
        val usherLambda = usherUrlBuilderMethod
    }
}