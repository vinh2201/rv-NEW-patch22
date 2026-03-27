package app.revanced.patches.youtube.video.information

import app.revanced.patcher.*
import app.revanced.patcher.extensions.instructions
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patches.youtube.shared.playbackSpeedOnItemClickParentMethodMatch
import app.revanced.patches.youtube.shared.videoQualityChangedMethodMatch
import app.revanced.util.getting
import app.revanced.util.using
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef

internal val BytecodePatchContext.playbackSpeedOnItemClickMethod by getting {
    firstMethodDeclaratively {
        name("onItemClick")
        accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
        returnType("V")
        parameterTypes("L", "L", "I", "J")
    }
} using { playbackSpeedOnItemClickParentMethodMatch.immutableMethod }

internal val BytecodePatchContext.playerControllerSetTimeReferenceMethodMatch by
composingFirstMethod("Media progress reported outside media playback: ") {
    opcodes(
        Opcode.INVOKE_DIRECT_RANGE,
        Opcode.IGET_OBJECT,
    )
}

internal val BytecodePatchContext.playVideoCheckVideoStreamingDataResponseMethod by gettingFirstImmutableMethodDeclaratively {
    instructions("playVideo called on player response with no videoStreamingData."())
}

internal val BytecodePatchContext.seekMethod by getting {
    firstImmutableMethodDeclaratively {
        instructions(
            anyOf(
                // 20.xx
                "Attempting to seek during an ad" { equals(it) }, // Must use custom comparison block, otherwise string is added to stringsList for lookup
                // 21.02+
                "currentPositionMs." { equals(it) }
            )
        )
    }
} using { playVideoCheckVideoStreamingDataResponseMethod }

internal val BytecodePatchContext.videoLengthMethodMatch by getting {
    firstMethodComposite {
        opcodes(
            Opcode.MOVE_RESULT_WIDE,
            Opcode.CMP_LONG,
            Opcode.IF_LEZ,
            Opcode.IGET_OBJECT,
            Opcode.CHECK_CAST,
            Opcode.INVOKE_VIRTUAL,
            Opcode.MOVE_RESULT_WIDE,
            Opcode.GOTO,
            Opcode.INVOKE_VIRTUAL,
            Opcode.MOVE_RESULT_WIDE,
            Opcode.CONST_4,
            Opcode.INVOKE_VIRTUAL,
        )
    }
} using {
    firstImmutableMethodDeclaratively {
        returnType("V")
        instructions("timed_markers_width"())
    }
}

internal val BytecodePatchContext.mdxPlayerDirectorSetVideoStageMethod by gettingFirstImmutableMethodDeclaratively {
    instructions("MdxDirector setVideoStage ad should be null when videoStage is not an Ad state "())
}

internal val BytecodePatchContext.mdxSeekMethod by getting {
    firstMethodDeclaratively {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
        returnType("Z")
        parameterTypes("J", "L")
        opcodes(
            Opcode.INVOKE_VIRTUAL,
            Opcode.MOVE_RESULT,
            Opcode.RETURN,
        )
        custom {
            // The instruction count is necessary here to avoid matching the relative version
            // of the seek method we're after, which has the same function signature as the
            // regular one, is in the same class, and even has the exact same 3 opcodes pattern.
            instructions.count() == 3
        }
    }
} using { mdxPlayerDirectorSetVideoStageMethod }

internal val BytecodePatchContext.mdxSeekRelativeMethod by getting {
    firstMethodDeclaratively {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
        // Return type is boolean up to 19.39, and void with 19.39+.
        parameterTypes("J", "L")
        opcodes(
            Opcode.IGET_OBJECT,
            Opcode.INVOKE_INTERFACE,
        )
    }
} using { mdxPlayerDirectorSetVideoStageMethod }

internal val BytecodePatchContext.seekRelativeMethod by getting {
    firstMethodDeclaratively {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
        // Return type is boolean up to 19.39, and void with 19.39+.
        parameterTypes("J", "L")
        opcodes(
            Opcode.ADD_LONG_2ADDR,
            Opcode.INVOKE_VIRTUAL,
        )
    }
} using { playVideoCheckVideoStreamingDataResponseMethod }

internal val BytecodePatchContext.playerStatusEnumMethod by gettingFirstImmutableMethodDeclaratively(
    "NEW",
    "PLAYBACK_PENDING",
    "PLAYBACK_LOADED",
    "PLAYBACK_INTERRUPTED",
    "INTERSTITIAL_REQUESTED",
    "INTERSTITIAL_PLAYING",
    "VIDEO_PLAYING",
    "ENDED",
) {
    accessFlags(AccessFlags.STATIC, AccessFlags.CONSTRUCTOR)
}

context(context: BytecodePatchContext)
internal fun ClassDef.getPlayerStatusMethod() =
    firstMethodDeclaratively {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
        returnType("V")
        parameterTypes(context.playerStatusEnumMethod.immutableClassDef.type)
        instructions(
            // The opcode for the first index of the method is sget-object.
            // Even in sufficiently old versions, such as YT 17.34, the opcode for the first index is sget-object.
            Opcode.SGET_OBJECT(),
            method { name == "plus" && definingClass == "Lj$/time/Instant;" },
        )
    }

internal val BytecodePatchContext.playbackSpeedMenuSpeedChangedMethodMatch by getting {
    firstMethodComposite {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
        returnType("L")
        parameterTypes("L")
        instructions(allOf(Opcode.IGET(), field { type == "F" }))
    }
} using { videoQualityChangedMethodMatch.immutableMethod }

internal val BytecodePatchContext.playbackSpeedClassMethod by gettingFirstMethodDeclaratively(
    "PLAYBACK_RATE_MENU_BOTTOM_SHEET_FRAGMENT",
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
    returnType("L")
    parameterTypes("L")
    opcodes(Opcode.RETURN_OBJECT)
}

/**
 * YouTube 20.19 and lower.
 */
internal val BytecodePatchContext.videoQualityLegacyMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/google/android/libraries/youtube/innertube/model/media/VideoQuality;")
    accessFlags(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR)
    parameterTypes(
        "I", // Resolution.
        "Ljava/lang/String;", // Human readable resolution: "480p", "1080p Premium", etc
        "Z",
        "L",
    )
}


internal val BytecodePatchContext.playbackStartDescriptorToStringMethodMatch by composingFirstMethod {
    name("toString")
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("Ljava/lang/String;")
    instructions(
        method { toString() == "Ljava/util/Locale;->getDefault()Ljava/util/Locale;" },
        // First method call after Locale is the video ID.
        method { returnType == "Ljava/lang/String;" && parameterTypes.isEmpty() },
        "PlaybackStartDescriptor:"(String::startsWith)
    )
}

// Class name is un-obfuscated in targets before 21.01.
internal val BytecodePatchContext.videoQualityMethod by gettingFirstMethodDeclaratively {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR)
    parameterTypes(
        "I", // Resolution.
        "L",
        "Ljava/lang/String;", // Human readable resolution: "480p", "1080p Premium", etc
        "Z",
        "L",
    )
}

internal val BytecodePatchContext.videoQualitySetterMethod by gettingFirstMethodDeclaratively(
    "menu_item_video_quality",
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")
    parameterTypes("[L", "I", "Z")
    opcodes(
        Opcode.IF_EQZ,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.INVOKE_VIRTUAL,
        Opcode.IPUT_BOOLEAN,
    )
}

internal val BytecodePatchContext.setVideoQualityMethod by getting {
    firstMethodDeclaratively {
        returnType("V")
        parameterTypes("L")
        opcodes(
            Opcode.IGET_OBJECT,
            Opcode.IPUT_OBJECT,
            Opcode.IGET_OBJECT,
        )
    }
} using { videoQualitySetterMethod }
