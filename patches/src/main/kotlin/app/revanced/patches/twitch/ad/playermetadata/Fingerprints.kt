package app.revanced.patches.twitch.ad.playermetadata

import app.revanced.patcher.gettingFirstMethodDeclarativelyOrNull
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.strings

internal val BytecodePatchContext.streamMetadataParserMethod by gettingFirstMethodDeclarativelyOrNull {
    strings(
        "twitch-stitched-ad",
        "twitch-ad-quartile",
        "X-TV-TWITCH-AD-QUARTILE",
        "twitch-maf-ad",
    )
}
internal val BytecodePatchContext.onSurestreamAdStartedClassMethod by gettingFirstMethodDeclarativelyOrNull {
    strings("OnSurestreamAdStarted(adMetadata=")
}

internal val BytecodePatchContext.onSurestreamAdQuartileClassMethod by gettingFirstMethodDeclarativelyOrNull {
    strings("OnSurestreamAdQuartile(adQuartileEvent=")
}

internal val BytecodePatchContext.onMultiformatAdRequestedClassMethod by gettingFirstMethodDeclarativelyOrNull {
    strings("OnMultiformatAdRequested(multiAdFormatMetadata=")
}

internal val BytecodePatchContext.onPbypPreflightMessageClassMethod by gettingFirstMethodDeclarativelyOrNull {
    strings("OnPbypPreflightMessage(pbypPreflightMessage=")
}

internal val BytecodePatchContext.onTriggerUrlSetClassMethod by gettingFirstMethodDeclarativelyOrNull {
    strings("OnTriggerUrlSet(url=")
}
