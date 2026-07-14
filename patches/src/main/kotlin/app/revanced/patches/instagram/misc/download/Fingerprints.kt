package app.revanced.patches.instagram.misc.download

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.util.getting
import app.revanced.util.using
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method

internal const val MEDIA_OPTION_CLASS_DESCRIPTOR =
    "Lcom/instagram/feed/media/mediaoption/MediaOption\$Option;"

private const val MEDIA_CLASS_DESCRIPTOR = "Lcom/instagram/feed/media/Media;"
private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"

/** A reel options row-adder: `(Context, click listener, label, icon)`. */
internal fun Method.isClipsRowAdder() =
    returnType == "V" &&
        accessFlags and AccessFlags.STATIC.value == 0 &&
        parameterTypes.size == 4 &&
        parameterTypes[0] == "Landroid/content/Context;" &&
        parameterTypes[1] == "Landroid/view/View\$OnClickListener;" &&
        parameterTypes[2] == "Ljava/lang/String;" &&
        parameterTypes[3] == "I"

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
        custom {
            parameterTypes.size == 6 &&
                parameterTypes[1] == MEDIA_OPTION_CLASS_DESCRIPTOR &&
                parameterTypes[3] == CHAR_SEQUENCE &&
                parameterTypes[4] == "Ljava/util/ArrayList;" &&
                parameterTypes[5] == "Z"
        }
    }
} using { mediaOptionsMenuCreatorMethod }

// Dispatches a tapped post "..." option.
internal val BytecodePatchContext.postOptionClickMethod by getting {
    firstMethodDeclaratively {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
        returnType("V")
        custom { parameterTypes.size == 1 && parameterTypes[0] == MEDIA_OPTION_CLASS_DESCRIPTOR }
    }
} using { navigateToCameraMethod }

// Builds the story "..." dialog, its 3rd parameter type is the story helper class.
internal val BytecodePatchContext.storyDialogMethod by gettingFirstMethodDeclaratively {
    accessFlags(AccessFlags.STATIC)
    returnType("Landroid/app/Dialog;")
    custom {
        parameterTypes.size == 4 &&
            parameterTypes[0] == "Landroid/content/DialogInterface\$OnClickListener;" &&
            parameterTypes[1] == "Landroid/content/DialogInterface\$OnDismissListener;" &&
            parameterTypes[3] == "[Ljava/lang/CharSequence;"
    }
}

// Returns the story "..." option labels.
internal fun BytecodePatchContext.storyOptionsMethod(storyHelperClass: String) =
    firstMethodDeclaratively {
        definingClass(storyHelperClass)
        accessFlags(AccessFlags.STATIC)
        returnType("[Ljava/lang/CharSequence;")
        custom { parameterTypes.size == 1 }
    }

// Dispatches a tapped story dialog option: `(helper, selected label)`.
internal fun BytecodePatchContext.storyOptionClickMethod(storyHelperClass: String) =
    firstMethodDeclaratively {
        definingClass(storyHelperClass)
        accessFlags(AccessFlags.STATIC)
        returnType("V")
        custom {
            parameterTypes.size == 2 &&
                parameterTypes[0] == storyHelperClass &&
                parameterTypes[1] == "Ljava/lang/String;"
        }
    }

// A story bottom-sheet/context-menu row dispatcher whose last parameter is the selected label.
internal fun BytecodePatchContext.storyOptionDispatchMethod(storyHelperClass: String, methodName: String) =
    firstMethodDeclaratively {
        definingClass(storyHelperClass)
        name(methodName)
        accessFlags(AccessFlags.STATIC)
        returnType("V")
        custom { parameterTypes.last() == CHAR_SEQUENCE }
    }

// Shows the reel "..." options sheet, its 2nd parameter is the config that accumulates the rows.
internal val BytecodePatchContext.clipsShowMethod by getting {
    firstMethodDeclaratively {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
        returnType("V")
        custom { parameterTypes.size >= 2 && parameterTypes[0] == "Landroid/view/View;" }
    }
} using { clipsOrganicMoreOptionsMethod }

// A reel options row-adder, used to reach its class and enumerate the row-adder variants.
internal fun BytecodePatchContext.clipsRowAdderMethod(optionsConfigClass: String) =
    firstMethodDeclaratively {
        definingClass(optionsConfigClass)
        custom { isClipsRowAdder() }
    }
