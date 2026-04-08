package app.revanced.patches.youtube.interaction.seekbar

import app.revanced.patcher.accessFlags
import app.revanced.patcher.after
import app.revanced.patcher.afterAtMost
import app.revanced.patcher.allOf
import app.revanced.patcher.composingFirstMethod
import app.revanced.patcher.custom
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.instructions
import app.revanced.patcher.field
import app.revanced.patcher.firstImmutableMethodDeclaratively
import app.revanced.patcher.firstMethodComposite
import app.revanced.patcher.firstMethodDeclaratively
import app.revanced.patcher.gettingFirstImmutableMethodDeclaratively
import app.revanced.patcher.instructions
import app.revanced.patcher.invoke
import app.revanced.patcher.method
import app.revanced.patcher.name
import app.revanced.patcher.opcodes
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import app.revanced.patcher.type
import app.revanced.util.getting
import app.revanced.util.literal
import app.revanced.util.using
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

private val BytecodePatchContext.swipingUpGestureParentMethod by gettingFirstImmutableMethodDeclaratively {
    returnType("Z")
    parameterTypes()
    instructions(
        45379021L(), // Swipe up fullscreen feature flag.
    )
}


internal val BytecodePatchContext.showSwipingUpGuideMethod by getting {
    firstMethodDeclaratively {
        accessFlags(AccessFlags.FINAL)
        returnType("Z")
        parameterTypes()
        instructions(1L())
    }
} using { swipingUpGestureParentMethod }


internal val BytecodePatchContext.allowSwipingUpGestureMethod by getting {
    firstMethodDeclaratively {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
        returnType("V")
        parameterTypes("L")
    }
} using { swipingUpGestureParentMethod }

internal val BytecodePatchContext.disableFastForwardGestureMethodMatch by composingFirstMethod {
    definingClass("/NextGenWatchLayout;")
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("Z")
    parameterTypes()
    opcodes(
        Opcode.IF_EQZ,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT,
    )
    custom { instructions.count() > 30 }
}

internal val BytecodePatchContext.onTouchEventHandlerMethodMatch by composingFirstMethod {
    name("onTouchEvent")
    accessFlags(AccessFlags.PUBLIC, AccessFlags.PUBLIC)
    returnType("Z")
    parameterTypes("L")
    opcodes(
        Opcode.INVOKE_VIRTUAL, // nMethodReference
        Opcode.RETURN,
        Opcode.IGET_OBJECT,
        Opcode.IGET_BOOLEAN,
        Opcode.IF_EQZ,
        Opcode.INVOKE_VIRTUAL,
        Opcode.RETURN,
        Opcode.INT_TO_FLOAT,
        Opcode.INT_TO_FLOAT,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT,
        Opcode.IF_EQZ,
        Opcode.INVOKE_VIRTUAL,
        Opcode.INVOKE_VIRTUAL, // oMethodReference
    )
}

internal val BytecodePatchContext.tapToSeekMethodMatch by composingFirstMethod {
    name("onTouchEvent")
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("Z")
    parameterTypes("Landroid/view/MotionEvent;")
    instructions(
        Int.MAX_VALUE.toLong()(),
        allOf(Opcode.NEW_INSTANCE(), type("Landroid/graphics/Point;")),
        after(method { toString() == "Landroid/graphics/Point;-><init>(II)V" }),
        after(method { toString() == "Lj$/util/Optional;->of(Ljava/lang/Object;)Lj$/util/Optional;" }),
        after(Opcode.MOVE_RESULT_OBJECT()),
        after(allOf(Opcode.IPUT_OBJECT(), field { type == "Lj$/util/Optional;" })),
        afterAtMost(10, Opcode.INVOKE_VIRTUAL()),
    )
}

internal val BytecodePatchContext.slideToSeekMethodMatch by composingFirstMethod {
    accessFlags(AccessFlags.PRIVATE, AccessFlags.FINAL)
    returnType("V")
    parameterTypes("Landroid/view/View;", "F")
    opcodes(
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT,
        Opcode.IF_EQZ,
        Opcode.GOTO_16,
    )
    literal { 67108864 }
}

internal val BytecodePatchContext.fullscreenLargeSeekbarFeatureFlagMethodMatch by composingFirstMethod {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("Z")
    parameterTypes()
    instructions(45691569L())
}

internal val BytecodePatchContext.videoStreamingDataAllowSeekingMethod by getting {
    firstMethodDeclaratively {
        returnType("Z")
        parameterTypes()
        instructions(
            8L(),
            after(Opcode.IF_EQ()),
            after(1L()) // Another method in the same class almost matches this but uses 0 here.
        )
    }
} using {
    firstImmutableMethodDeclaratively {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
        returnType("Ljava/lang/String;")
        name("toString")
        instructions("VideoStreamingData(itags="())
    }
}