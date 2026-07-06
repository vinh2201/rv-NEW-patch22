package app.revanced.patches.twitch.analytics

import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext

internal val BytecodePatchContext.trackEventMethod by gettingFirstMethodDeclaratively("x_untrusted_minute-watched_spade")

internal val BytecodePatchContext.analyticsFeatureInitMethod by gettingFirstMethodDeclaratively {
    definingClass("/AnalyticsFeature;")
    name("<init>")
}
