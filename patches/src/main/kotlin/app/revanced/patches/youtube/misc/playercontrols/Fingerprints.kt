package app.revanced.patches.youtube.misc.playercontrols

import app.revanced.patcher.accessFlags
import app.revanced.patcher.after
import app.revanced.patcher.afterAtMost
import app.revanced.patcher.allOf
import app.revanced.patcher.composingFirstMethod
import app.revanced.patcher.definingClass
import app.revanced.patcher.firstImmutableMethodDeclaratively
import app.revanced.patcher.firstMethodComposite
import app.revanced.patcher.firstMethodDeclaratively
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.instructions
import app.revanced.patcher.invoke
import app.revanced.patcher.method
import app.revanced.patcher.name
import app.revanced.patcher.opcodes
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import app.revanced.patcher.type
import app.revanced.patches.shared.misc.mapping.ResourceType
import app.revanced.patches.youtube.layout.player.overlay.createPlayerOverviewMethodMatch
import app.revanced.patches.youtube.layout.sponsorblock.controlsOverlayMethodMatch
import app.revanced.util.getting
import app.revanced.util.using
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val BytecodePatchContext.playerControlsVisibilityEntityModelMethodMatch by composingFirstMethod {
    name("getPlayerControlsVisibility")
    accessFlags(AccessFlags.PUBLIC)
    returnType("L")
    parameterTypes()
    opcodes(
        Opcode.IGET,
        Opcode.INVOKE_STATIC,
    )
}

internal val BytecodePatchContext.motionEventMethodMatch by getting {
    firstMethodComposite {
        returnType("V")
        parameterTypes("Landroid/view/MotionEvent;")
        instructions(method("setTranslationY"))
    }
} using {
    firstImmutableMethodDeclaratively {
        returnType("V")
        parameterTypes()
        instructions(
            method("setFocusableInTouchMode"),
            ResourceType.ID("inset_overlay_view_layout"),
            ResourceType.ID("scrim_overlay"),
        )
    }
}

internal val BytecodePatchContext.playerControlsExtensionHookListenersExistMethod by gettingFirstMethodDeclaratively {
    name("fullscreenButtonVisibilityCallbacksExist")
    definingClass(EXTENSION_CLASS_DESCRIPTOR)
    accessFlags(AccessFlags.PRIVATE, AccessFlags.STATIC)
    returnType("Z")
    parameterTypes()
}

internal val BytecodePatchContext.playerControlsExtensionHookMethod by gettingFirstMethodDeclaratively {
    name("fullscreenButtonVisibilityChanged")
    definingClass(EXTENSION_CLASS_DESCRIPTOR)
    accessFlags(AccessFlags.PRIVATE, AccessFlags.STATIC)
    returnType("V")
    parameterTypes("Z")
}

internal val BytecodePatchContext.playerTopControlsInflateMethodMatch by composingFirstMethod {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")
    parameterTypes()
    instructions(
        ResourceType.ID("controls_layout_stub"),
        method { name == "inflate" && definingClass == "Landroid/view/ViewStub;" },
        after(Opcode.MOVE_RESULT_OBJECT()),
    )
}

internal val BytecodePatchContext.playerBottomControlsInflateMethodMatch by composingFirstMethod {
    returnType("Ljava/lang/Object;")
    parameterTypes()
    instructions(
        ResourceType.ID("bottom_ui_container_stub"),
        method { name == "inflate" && definingClass == "Landroid/view/ViewStub;" },
        after(Opcode.MOVE_RESULT_OBJECT()),
    )
}

/**
 * Matches same method as [controlsOverlayMethodMatch] and [createPlayerOverviewMethodMatch].
 */
internal val BytecodePatchContext.playerBottomGradientScrimMethodMatch by composingFirstMethod {
    returnType("V")
    parameterTypes()
    instructions(
        ResourceType.ID("bottom_gradient_scrim_overlay"),
        afterAtMost(10, allOf(Opcode.CHECK_CAST(), type("Landroid/widget/ImageView;"))),
        Opcode.NEW_INSTANCE(),
        Opcode.IPUT_OBJECT(),
        after(Opcode.IPUT_OBJECT()),
        after(Opcode.IPUT_OBJECT()),
    )
}

internal val BytecodePatchContext.overlayViewInflateMethodMatch by composingFirstMethod {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")
    parameterTypes("Landroid/view/View;")
    instructions(
        ResourceType.ID("heatseeker_viewstub"),
        ResourceType.ID("fullscreen_button"),
        Opcode.CHECK_CAST(),
    )
}

internal val BytecodePatchContext.controlsOverlayVisibilityMethod by getting {
    firstMethodDeclaratively {
        accessFlags(AccessFlags.PRIVATE, AccessFlags.FINAL)
        returnType("V")
        parameterTypes("Z", "Z")
    }
} using { playerTopControlsInflateMethodMatch.immutableMethod }

internal val BytecodePatchContext.playerBottomControlsExploderFeatureFlagMethod by gettingFirstMethodDeclaratively {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("Z")
    parameterTypes()
    instructions(45643739L())
}

internal val BytecodePatchContext.playerControlsLargeOverlayButtonsFeatureFlagMethod by gettingFirstMethodDeclaratively {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("Z")
    parameterTypes()
    instructions(45709810L())
}

internal val BytecodePatchContext.playerControlsFullscreenLargeButtonsFeatureFlagMethod by gettingFirstMethodDeclaratively {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("Z")
    parameterTypes()
    instructions(45686474L())
}

internal val BytecodePatchContext.playerControlsButtonStrokeFeatureFlagMethod by gettingFirstMethodDeclaratively {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("Z")
    parameterTypes()
    instructions(45713296L())
}
