package app.revanced.patches.twitch.ad.experimental

import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.returnType
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext

const val DISPLAY_AD_CONTAINER_CLASS = "/DisplayAdContainer;"
const val DISPLAY_AD_RESPONSE_CLASS = "/DisplayAdResponse;"
const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/revanced/extension/twitch/patches/ExperimentalAdRemoverPatch;"

internal val BytecodePatchContext.checkerAd1Method by gettingFirstMethodDeclaratively("adData", "ad_client_pod_start") {
    name("invoke")
    returnType("Ljava/lang/Object;")
    parameterTypes("Ljava/lang/Object;", "Ljava/lang/Object;")
}

internal val BytecodePatchContext.getTreatmentAndRecordTriggerMethod by gettingFirstMethodDeclaratively {
    definingClass("/MAPWeblabClient;")
    name("getTreatmentAndRecordTrigger")
}

internal val BytecodePatchContext.getAccessTokenMethod by gettingFirstMethodDeclaratively {
    definingClass("/MinervaOAuthProvider;")
    name("getAccessToken")
    returnType("Ljava/lang/String;")
}

internal val BytecodePatchContext.getAccessToken2Method by gettingFirstMethodDeclaratively {
    definingClass("/GetTokenResult;")
    name("getAccessToken")
    returnType("Ljava/lang/String;")
}

internal val BytecodePatchContext.onActivityResultMethod by gettingFirstMethodDeclaratively {
    definingClass("/GetAuthenticatorResultsActivity;")
    name("onActivityResult")
    returnType("V")
    parameterTypes("I", "I", "Landroid/content/Intent;")
}

internal val BytecodePatchContext.getMaxHeightPxMethod by gettingFirstMethodDeclaratively {
    definingClass(DISPLAY_AD_CONTAINER_CLASS)
    name("getMaxHeightPx")
    returnType("I")
}

internal val BytecodePatchContext.getMaxWidthPxMethod by gettingFirstMethodDeclaratively {
    definingClass(DISPLAY_AD_CONTAINER_CLASS)
    name("getMaxWidthPx")
    returnType("I")
}

internal val BytecodePatchContext.getAdHeightPxMethod by gettingFirstMethodDeclaratively {
    definingClass(DISPLAY_AD_CONTAINER_CLASS)
    name("getAdHeightPx")
    returnType("Ljava/lang/Integer;")
}

internal val BytecodePatchContext.getAdWidthPxMethod by gettingFirstMethodDeclaratively {
    definingClass(DISPLAY_AD_CONTAINER_CLASS)
    name("getAdWidthPx")
    returnType("Ljava/lang/Integer;")
}

internal val BytecodePatchContext.getAdViewMethod by gettingFirstMethodDeclaratively {
    definingClass(DISPLAY_AD_CONTAINER_CLASS)
    name("getAdView")
    returnType("Landroid/view/View;")
}

internal val BytecodePatchContext.onMeasureMethod by gettingFirstMethodDeclaratively {
    definingClass(DISPLAY_AD_CONTAINER_CLASS)
    name("onMeasure")
    returnType("V")
    parameterTypes("I", "I")
}

internal val BytecodePatchContext.getHeightMethod by gettingFirstMethodDeclaratively {
    definingClass(DISPLAY_AD_RESPONSE_CLASS)
    name("getHeight")
    returnType("I")
}

internal val BytecodePatchContext.getWidthMethod by gettingFirstMethodDeclaratively {
    definingClass(DISPLAY_AD_RESPONSE_CLASS)
    name("getWidth")
    returnType("I")
}