package app.revanced.patches.youtube.layout.hide.relatedvideooverlay

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patches.shared.misc.mapping.ResourceType
import app.revanced.util.getting
import app.revanced.util.using

internal val BytecodePatchContext.relatedEndScreenResultsMethod by getting {
    firstMethodDeclaratively {
        returnType("V")
        parameterTypes(
            "I",
            "Z",
            "I",
        )
    }
} using {
    firstImmutableMethodDeclaratively {
        returnType("V")
        instructions(
            ResourceType.LAYOUT("app_related_endscreen_results"),
        )
    }
}
