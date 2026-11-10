package app.revanced.patches.viber.misc.analytics

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.returnEarly
import java.util.logging.Logger

@Suppress("unused")
val disableAnalyticsPatch = bytecodePatch(
    name = "Disable analytics",
    description = "(PORTED) Stops Mixpanel, Adjust, Braze, Firebase, Crashlytics and Snap Kit from collecting and " +
        "sending usage data. Works with Viber 23.x, 26.x and 29.x.",
) {
    compatibleWith("com.viber.voip")

    apply {
        val logger = Logger.getLogger(this::class.java.name)
        var patched = 0

        findTrackerAllowedMethod()?.let { method ->
            firstMethod(method).returnEarly(false)
            patched++
        } ?: logger.warning("Could not find the trackers allowed method.")

        findSnapKitInitMethod()?.let { method ->
            firstMethod(method).returnEarly(false)
            patched++
        } ?: logger.warning("Could not find Snap Kit.")

        findFirebaseSessionsMethod()?.let { method ->
            firstMethod(method).returnEarly(false)
            patched++
        } ?: logger.warning("Could not find Firebase Sessions.")

        findCrashlyticsEnabledMethod()?.let { method ->
            firstMethod(method).returnEarly(false)
            patched++
        } ?: logger.warning("Could not find Crashlytics.")

        // Viber 29.x: trackers are also disabled when the consent is applied.
        findTrackerConsent()?.let { consent ->
            val method = firstMethod(consent.method)
            val setEnabled = consent.setEnabled

            // v0 is a local register: the method is `void a(Tracker)`, so p1 is the tracker.
            method.addInstructions(
                0,
                """
                    const/4 v0, 0x0
                    invoke-interface { p1, v0 }, ${setEnabled.definingClass}->${setEnabled.name}(Z)V
                    return-void
                """,
            )
            patched++
        }

        // Firebase / Google Analytics: the manifest flags always say "disabled".
        val metadataFlags = findMetadataFlags()
        if (metadataFlags.isEmpty()) logger.warning("Could not find the Firebase Analytics manifest flags.")

        metadataFlags
            .groupBy { it.method }
            .forEach { (method, flags) ->
                val mutableMethod = firstMethod(method)

                // Insert from the last one to the first one, so the indexes stay valid.
                flags.sortedByDescending { it.moveResultIndex }.forEach { flag ->
                    mutableMethod.addInstructions(
                        flag.moveResultIndex + 1,
                        "sget-object v${flag.register}, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;",
                    )
                }
                patched++
            }

        if (patched == 0) {
            throw PatchException("Could not find any analytics related method. This Viber version is not supported.")
        }
    }
}
