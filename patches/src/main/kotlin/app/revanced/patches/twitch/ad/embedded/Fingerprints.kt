package app.revanced.patches.twitch.ad.embedded

import app.revanced.patcher.gettingFirstMethodDeclarativelyOrNull
import app.revanced.patcher.strings
import app.revanced.patcher.patch.BytecodePatchContext


internal val BytecodePatchContext.usherUrlBuilderMethod by gettingFirstMethodDeclarativelyOrNull {
    strings("usher.ttvnw.net", "force_preroll", "play_session_id")
}