@file:Suppress("SpellCheckingInspection")

package app.revanced.patches.youtube.layout.miniplayer

import app.revanced.patcher.accessFlags
import app.revanced.patcher.afterAtMost
import app.revanced.patcher.allOf
import app.revanced.patcher.anyOf
import app.revanced.patcher.composingFirstMethod
import app.revanced.patcher.firstImmutableMethodDeclaratively
import app.revanced.patcher.firstMethodComposite
import app.revanced.patcher.firstMethodDeclaratively
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.instructions
import app.revanced.patcher.invoke
import app.revanced.patcher.method
import app.revanced.patcher.opcodes
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import app.revanced.patcher.type
import app.revanced.patches.shared.misc.mapping.ResourceType
import app.revanced.util.getting
import app.revanced.util.using
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal const val MINIPLAYER_MODERN_FEATURE_KEY = 45622882L

internal const val MINIPLAYER_MODERN_TYPE_1_FEATURE_KEY = 45623000L
internal const val MINIPLAYER_MODERN_TYPE_2_FEATURE_KEY = 45623273L
internal const val MINIPLAYER_MODERN_TYPE_3_FEATURE_KEY = 45623076L
internal const val MINIPLAYER_MODERN_TYPE_4_FEATURE_KEY = 45674402L
internal const val MINIPLAYER_DOUBLE_TAP_FEATURE_KEY = 45628823L
internal const val MINIPLAYER_DRAG_DROP_FEATURE_KEY = 45628752L
internal const val MINIPLAYER_HORIZONTAL_DRAG_FEATURE_KEY = 45658112L
internal const val MINIPLAYER_ROUNDED_CORNERS_FEATURE_KEY = 45652224L
internal const val MINIPLAYER_INITIAL_SIZE_FEATURE_KEY = 45640023L
internal const val MINIPLAYER_DISABLED_FEATURE_KEY = 45657015L
internal const val MINIPLAYER_ANIMATED_EXPAND_FEATURE_KEY = 45644360L

// In later targets this feature flag does nothing and is dead code.
internal const val MINIPLAYER_MODERN_FEATURE_LEGACY_KEY = 45630429L

internal val BytecodePatchContext.miniplayerModernConstructorMethod by gettingFirstMethodDeclaratively {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR)
    instructions(
        MINIPLAYER_MODERN_TYPE_1_FEATURE_KEY(),
    )
}

internal val BytecodePatchContext.miniplayerModernAddViewListenerMethod by getting {
    firstMethodDeclaratively {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
        returnType("V")
        parameterTypes("Landroid/view/View;")
    }
} using {
    firstImmutableMethodDeclaratively {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
        returnType("Ljava/lang/String;")
        parameterTypes()
        instructions(
            "player_overlay_modern_mini_player_controls"(),
        )
    }
}

internal val BytecodePatchContext.miniplayerModernCloseButtonMethodMatch by getting {
    firstMethodComposite {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
        returnType("L")
        parameterTypes()
        instructions(
            ResourceType.ID("modern_miniplayer_close"),
            allOf(Opcode.CHECK_CAST(), type("Landroid/widget/ImageView;")),
        )
    }
} using { miniplayerModernAddViewListenerMethod }

internal val BytecodePatchContext.miniplayerModernExpandButtonMethodMatch by getting {
    firstMethodComposite {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
        returnType("L")
        parameterTypes()
        instructions(
            ResourceType.ID("modern_miniplayer_expand"),
            allOf(Opcode.CHECK_CAST(), type("Landroid/widget/ImageView;")),
        )
    }
} using { miniplayerModernAddViewListenerMethod }

internal val BytecodePatchContext.miniplayerModernForwardButtonMethodMatch by getting {
    firstMethodComposite {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
        returnType("L")
        parameterTypes()
        instructions(
            ResourceType.ID("modern_miniplayer_forward_button"),
            afterAtMost(5, Opcode.MOVE_RESULT_OBJECT()),
        )
    }
} using { miniplayerModernAddViewListenerMethod }

internal val BytecodePatchContext.miniplayerModernOverlayViewMethodMatch by getting {
    firstMethodComposite {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
        parameterTypes()
        instructions(
            ResourceType.ID("scrim_overlay"),
            afterAtMost(5, Opcode.MOVE_RESULT_OBJECT()),
        )
    }
} using { miniplayerModernAddViewListenerMethod }

internal val BytecodePatchContext.miniplayerModernRewindButtonMethodMatch by getting {
    firstMethodComposite {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
        returnType("L")
        parameterTypes()
        instructions(
            ResourceType.ID("modern_miniplayer_rewind_button"),
            afterAtMost(5, Opcode.MOVE_RESULT_OBJECT()),
        )
    }
} using { miniplayerModernAddViewListenerMethod }

internal val BytecodePatchContext.miniplayerModernActionButtonMethodMatch by getting {
    firstMethodComposite {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
        returnType("L")
        parameterTypes()
        instructions(
            ResourceType.ID("modern_miniplayer_overlay_action_button"),
            afterAtMost(5, Opcode.MOVE_RESULT_OBJECT()),
        )
    }
} using { miniplayerModernAddViewListenerMethod }

internal val BytecodePatchContext.miniplayerMinimumSizeMethodMatch by composingFirstMethod {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR)
    instructions(
        ResourceType.DIMEN("miniplayer_max_size"),
        anyOf(
            // Default miniplayer width constant.
            192L(),
            192.0f.toRawBits().toLong()(), // 21.03+
        ),
        anyOf(
            // Default miniplayer height constant.
            128L(),
            128.0f.toRawBits().toLong()(), // 21.03+
        )
    )
}

internal val BytecodePatchContext.miniplayerOverrideMethodMatch by composingFirstMethod {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("L")
    instructions(
        "appName"(),
        afterAtMost(
            10,
            method { parameterTypes.count() == 1 && parameterTypes.first() == "Landroid/content/Context;" && returnType == "Z" },
        ),
    )
}

internal val BytecodePatchContext.miniplayerOverrideNoContextMethod by getting {
    firstMethodDeclaratively {
        accessFlags(AccessFlags.PRIVATE, AccessFlags.FINAL)
        returnType("Z")
        opcodes(
            Opcode.IGET_BOOLEAN, // Anchor to insert the instruction.
        )
    }
} using {
    firstImmutableMethodDeclaratively {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
        returnType("V")
        parameterTypes("L")
        instructions(
            ResourceType.DIMEN("floaty_bar_button_top_margin"),
        )
    }
}

/**
 * 20.36 and lower. Codes appears to be removed in 20.37+
 */
internal val BytecodePatchContext.miniplayerResponseModelSizeCheckMethodMatch by composingFirstMethod {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("L")
    parameterTypes("Ljava/lang/Object;", "Ljava/lang/Object;")
    opcodes(
        Opcode.RETURN_OBJECT,
        Opcode.CHECK_CAST,
        Opcode.CHECK_CAST,
        Opcode.INVOKE_STATIC,
        Opcode.MOVE_RESULT,
        Opcode.IF_NEZ,
    )
}

internal val BytecodePatchContext.miniplayerOnCloseHandlerMethod by gettingFirstMethodDeclaratively {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("Z")
    instructions(
        MINIPLAYER_DISABLED_FEATURE_KEY(),
    )
}

internal const val YOUTUBE_PLAYER_OVERLAYS_LAYOUT_CLASS_NAME =
    "Lcom/google/android/apps/youtube/app/common/player/overlay/YouTubePlayerOverlaysLayout;"

internal val BytecodePatchContext.miniplayerSetIconsMethod by gettingFirstMethodDeclaratively {
    returnType("V")
    parameterTypes("I", "Ljava/lang/Runnable;")
    instructions(
        ResourceType.DRAWABLE("yt_fill_pause_white_36"),
        ResourceType.DRAWABLE("yt_fill_pause_black_36"),
    )
}
