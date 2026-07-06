package app.revanced.patches.twitch.ad.audio

import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType

const val AUDIO_AD_CLASS = "/AudioAd;"
const val AUDIO_AD_POD_CLASS = "/AudioAdsPod;"

internal val BytecodePatchContext.audioAdGetDurationMethod by gettingFirstMethodDeclaratively {
    definingClass(AUDIO_AD_CLASS)
    name("getDuration")
    returnType("F")
}

internal val BytecodePatchContext.audioAdGetDurationSecondsMethod by gettingFirstMethodDeclaratively {
    definingClass(AUDIO_AD_CLASS)
    name("getDurationSeconds")
    returnType("I")
}

internal val BytecodePatchContext.audioAdGetAudioAdsMethod by gettingFirstMethodDeclaratively {
    definingClass(AUDIO_AD_POD_CLASS)
    name("getAudioAds")
    returnType("Ljava/util/List;")
}
