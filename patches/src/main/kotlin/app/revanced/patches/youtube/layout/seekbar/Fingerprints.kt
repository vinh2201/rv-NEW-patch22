package app.revanced.patches.youtube.layout.seekbar

import app.revanced.patcher.accessFlags
import app.revanced.patcher.after
import app.revanced.patcher.afterAtMost
import app.revanced.patcher.anyOf
import app.revanced.patcher.composingFirstMethod
import app.revanced.patcher.custom
import app.revanced.patcher.definingClass
import app.revanced.patcher.firstMethodDeclaratively
import app.revanced.patcher.gettingFirstImmutableMethodDeclaratively
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.instructions
import app.revanced.patcher.invoke
import app.revanced.patcher.method
import app.revanced.patcher.opcodes
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import app.revanced.patches.shared.misc.mapping.ResourceType
import app.revanced.util.getting
import app.revanced.util.using
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val BytecodePatchContext.fullscreenSeekbarThumbnailsMethod by gettingFirstMethodDeclaratively {
    returnType("Z")
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    parameterTypes()
    instructions(
        45398577L(),
    )
}

internal val BytecodePatchContext.playerSeekbarColorMethodMatch by composingFirstMethod {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR)
    instructions(
        ResourceType.COLOR("inline_time_bar_played_not_highlighted_color"),
        ResourceType.COLOR("inline_time_bar_colorized_bar_played_color_dark"),
    )
}

// class is ControlsOverlayStyle in 20.32 and lower, and obfuscated in 20.33+
internal val BytecodePatchContext.setSeekbarClickedColorMethodMatch by composingFirstMethod(
    "YOUTUBE",
    "PREROLL",
    "POSTROLL",
    "REMOTE_LIVE",
    "AD_LARGE_CONTROLS",
) {
    opcodes(Opcode.CONST_HIGH16)
}

internal val BytecodePatchContext.shortsSeekbarColorMethodMatch by composingFirstMethod {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR)
    instructions(
        ResourceType.COLOR("reel_time_bar_played_color"),
    )
}

internal val BytecodePatchContext.playerSeekbarHandle1ColorMethodMatch by composingFirstMethod {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR)
    instructions(
        ResourceType.COLOR("inline_time_bar_live_seekable_range"),
        ResourceType.ATTR("ytStaticBrandRed"),
    )
}

internal val BytecodePatchContext.playerSeekbarHandle2ColorMethodMatch by composingFirstMethod {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR)
    parameterTypes("Landroid/content/Context;")
    instructions(
        ResourceType.ATTR("ytTextSecondary"),
        ResourceType.ATTR("ytStaticBrandRed"),
    )
}

internal val BytecodePatchContext.watchHistoryMenuUseProgressDrawableMethodMatch by composingFirstMethod {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")
    parameterTypes("L")
    instructions(
        method { name == "setMax" && definingClass == "Landroid/widget/ProgressBar;" },
        Opcode.MOVE_RESULT(),
        (-1712394514L)(),
    )
}

internal val BytecodePatchContext.lithoLinearGradientMethod by gettingFirstMethodDeclaratively {
    accessFlags(AccessFlags.STATIC)
    returnType("Landroid/graphics/LinearGradient;")
    parameterTypes("F", "F", "F", "F", "[I", "[F")
}

/**
 * 19.49+
 */
internal val BytecodePatchContext.playerLinearGradientMethodMatch by composingFirstMethod {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
    parameterTypes("I", "I", "I", "I", "Landroid/content/Context;", "I")
    returnType("Landroid/graphics/LinearGradient;")
    instructions(
        ResourceType.COLOR("yt_youtube_magenta"),

        afterAtMost(5, Opcode.FILLED_NEW_ARRAY()),
        after(Opcode.MOVE_RESULT_OBJECT()),
    )
}

internal const val LOTTIE_ANIMATION_VIEW_CLASS_TYPE = "Lcom/airbnb/lottie/LottieAnimationView;"

internal val BytecodePatchContext.lottieAnimationViewSetAnimationIntMethod by gettingFirstImmutableMethodDeclaratively {
    definingClass(LOTTIE_ANIMATION_VIEW_CLASS_TYPE)
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    parameterTypes("I")
    returnType("V")

    var methodDefiningClass = ""
    custom {
        methodDefiningClass = definingClass
        true
    }

    instructions(method { name == "isInEditMode" && definingClass == methodDefiningClass })
}

internal val BytecodePatchContext.lottieCompositionFactoryZipMethod by gettingFirstImmutableMethodDeclaratively {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
    parameterTypes(
        "Landroid/content/Context;",
        "Ljava/util/zip/ZipInputStream;",
        "Ljava/lang/String;"
    )
    returnType("L")
    instructions(
        "Unable to parse composition"(),
        " however it was not found in the animation."(),
    )
}

/**
 * [Original method](https://github.com/airbnb/lottie-android/blob/26ad8bab274eac3f93dccccfa0cafc39f7408d13/lottie/src/main/java/com/airbnb/lottie/LottieCompositionFactory.java#L386)
 */
internal val BytecodePatchContext.lottieCompositionFactoryFromJsonInputStreamMethod by getting {
    firstMethodDeclaratively {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
        parameterTypes("Ljava/io/InputStream;", "Ljava/lang/String;")
        returnType("L")
        instructions(
            anyOf(2L(), 3L()),
        )
    }
} using { lottieCompositionFactoryZipMethod }
