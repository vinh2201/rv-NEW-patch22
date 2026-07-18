package app.revanced.patches.instagram.misc.download

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.util.getting
import app.revanced.util.using
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction

internal const val MEDIA_OPTION_CLASS_DESCRIPTOR =
    "Lcom/instagram/feed/media/mediaoption/MediaOption\$Option;"

private const val MEDIA_CLASS_DESCRIPTOR = "Lcom/instagram/feed/media/Media;"
private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"

// Both reel row methods share this signature and differ only by the destructive/"red" boolean they forward.
private fun MutablePredicateList<Method>.addClipsRowSignature() {
    returnType("V")
    parameterTypes(
        "Landroid/content/Context;",
        "Landroid/view/View\$OnClickListener;",
        "Ljava/lang/String;",
        "I",
    )
}

// The destructive variant forwards the flag as const 1, the normal one as const 0.
private fun Method.forwardsDestructiveFlag() =
    implementation!!.instructions.any {
        it.opcode == Opcode.CONST_4 && (it as NarrowLiteralInstruction).narrowLiteral == 1
    }

// Marks the class that builds a post "..." menu's option rows.
internal val BytecodePatchContext.mediaOptionsMenuCreatorMethod by
    gettingFirstMethodDeclaratively("MediaOptionsOverflowMenuCreator")

// Passes the tapped media to the camera, lives on the post overflow helper that holds the media.
internal val BytecodePatchContext.navigateToCameraMethod by
    gettingFirstMethodDeclaratively(
        "MediaOptionsOverflowHelper:navigateToCamera: Failed to pass the media list into camera due to TransactionTooLarge for %s",
    )

// Marks the reel "..." options helper class that holds the reel media and activity.
internal val BytecodePatchContext.clipsOrganicMoreOptionsMethod by
    gettingFirstMethodDeclaratively("ClipsOrganicMoreOptionsHelper")

// Adds a single option row to a post's "..." menu.
internal val BytecodePatchContext.addOptionRowMethod by getting {
    firstMethodDeclaratively {
        accessFlags(AccessFlags.STATIC)
        returnType("V")
        parameterTypes("L", MEDIA_OPTION_CLASS_DESCRIPTOR, "L", CHAR_SEQUENCE, "Ljava/util/ArrayList;", "Z")
    }
} using { mediaOptionsMenuCreatorMethod }

// Dispatches a tapped post "..." option.
internal val BytecodePatchContext.postOptionClickMethod by getting {
    firstMethodDeclaratively {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
        returnType("V")
        parameterTypes(MEDIA_OPTION_CLASS_DESCRIPTOR)
    }
} using { navigateToCameraMethod }

// Builds the story "..." dialog, its 3rd parameter type is the story helper class.
internal val BytecodePatchContext.storyDialogMethod by gettingFirstMethodDeclaratively {
    accessFlags(AccessFlags.STATIC)
    returnType("Landroid/app/Dialog;")
    parameterTypes(
        "Landroid/content/DialogInterface\$OnClickListener;",
        "Landroid/content/DialogInterface\$OnDismissListener;",
        "L",
        "[Ljava/lang/CharSequence;",
    )
}

// Returns the story "..." option labels.
internal fun BytecodePatchContext.getStoryOptionsMethod(storyHelperClass: String) =
    firstMethod(
        firstImmutableClassDef(storyHelperClass).firstMethodDeclaratively {
            accessFlags(AccessFlags.STATIC)
            returnType("[Ljava/lang/CharSequence;")
            parameterTypes("L")
        },
    )

// Dispatches a tapped story dialog option: `(helper, selected label)`.
internal fun BytecodePatchContext.getStoryOptionClickMethod(storyHelperClass: String) =
    firstMethod(
        firstImmutableClassDef(storyHelperClass).firstMethodDeclaratively {
            accessFlags(AccessFlags.STATIC)
            returnType("V")
            parameterTypes(storyHelperClass, "Ljava/lang/String;")
        },
    )

// A story bottom-sheet/context-menu row dispatcher whose last parameter is the selected label.
internal fun BytecodePatchContext.getStoryOptionDispatchMethod(storyHelperClass: String, methodName: String) =
    firstMethod(
        firstImmutableClassDef(storyHelperClass).firstMethodDeclaratively {
            name(methodName)
            accessFlags(AccessFlags.STATIC)
            returnType("V")
            custom { parameterTypes.last() == CHAR_SEQUENCE }
        },
    )

// Shows the reel "..." options sheet, its 2nd parameter is the config that accumulates the rows.
internal val BytecodePatchContext.clipsShowMethod by getting {
    firstMethodDeclaratively {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
        returnType("V")
        custom { parameterTypes.size >= 2 && parameterTypes[0] == "Landroid/view/View;" }
    }
} using { clipsOrganicMoreOptionsMethod }

// Adds a normal (non-destructive) row to the reel "..." options sheet.
internal fun BytecodePatchContext.getAddClipsRowMethod(optionsConfigType: String) =
    firstImmutableClassDef(optionsConfigType).firstMethodDeclaratively {
        addClipsRowSignature()
        custom { !forwardsDestructiveFlag() }
    }

// Adds a destructive ("red") row to the reel "..." options sheet.
internal fun BytecodePatchContext.getAddDestructiveClipsRowMethod(optionsConfigType: String) =
    firstImmutableClassDef(optionsConfigType).firstMethodDeclaratively {
        addClipsRowSignature()
        custom { forwardsDestructiveFlag() }
    }
