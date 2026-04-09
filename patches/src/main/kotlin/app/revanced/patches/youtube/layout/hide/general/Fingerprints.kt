package app.revanced.patches.youtube.layout.hide.general

import app.revanced.patcher.accessFlags
import app.revanced.patcher.after
import app.revanced.patcher.afterAtMost
import app.revanced.patcher.allOf
import app.revanced.patcher.anyField
import app.revanced.patcher.composingFirstMethod
import app.revanced.patcher.custom
import app.revanced.patcher.field
import app.revanced.patcher.firstImmutableMethodDeclaratively
import app.revanced.patcher.firstMethodComposite
import app.revanced.patcher.firstMethodDeclaratively
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.immutableClassDef
import app.revanced.patcher.instructions
import app.revanced.patcher.invoke
import app.revanced.patcher.method
import app.revanced.patcher.opcodes
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import app.revanced.patcher.type
import app.revanced.patches.shared.misc.mapping.ResourceType
import app.revanced.patches.youtube.layout.buttons.navigation.wideSearchbarLayoutMethod
import app.revanced.util.getting
import app.revanced.util.using
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode


internal val BytecodePatchContext.hideShowMoreButtonSetViewMethodMatch by composingFirstMethod {
    returnType("V")

    var methodDefiningClass = ""
    custom {
        methodDefiningClass = definingClass
        true
    }
    instructions(
        ResourceType.ID("link_text_start"),
        allOf(
            Opcode.IPUT_OBJECT(),
            field { type == "Landroid/widget/TextView;" && definingClass == methodDefiningClass }),
        ResourceType.ID("expand_button_container"),
        allOf(
            Opcode.IPUT_OBJECT(),
            field { type == "Landroid/view/View;" && definingClass == methodDefiningClass })
    )
}

internal val BytecodePatchContext.hideShowMoreButtonGetParentViewMethod by getting {
    firstImmutableMethodDeclaratively {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
        returnType("Landroid/view/View;")
        parameterTypes()
    }
} using { hideShowMoreButtonSetViewMethodMatch.method }

internal val BytecodePatchContext.hideShowMoreButtonMethod by getting {
    firstMethodDeclaratively {
        returnType("V")
        parameterTypes("L", "Ljava/lang/Object;")
        instructions(
            allOf(
                Opcode.INVOKE_VIRTUAL(),
                method {
                    toString() == "Landroid/view/View;->setContentDescription(Ljava/lang/CharSequence;)V"
                }
            )
        )
    }
} using { hideShowMoreButtonSetViewMethodMatch.method }

/**
 * 20.21+
 */
internal val BytecodePatchContext.hideSubscribedChannelsBarConstructorMethodMatch by composingFirstMethod {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR)
    instructions(
        ResourceType.ID("parent_container"),
        afterAtMost(3, Opcode.MOVE_RESULT_OBJECT()),
        afterAtMost(
            5,
            allOf(Opcode.NEW_INSTANCE(), type($$"Landroid/widget/LinearLayout$LayoutParams;"))
        )
    )
    custom { immutableClassDef.anyField { type == "Landroid/support/v7/widget/RecyclerView;" } }
}

/**
 * 20.21
 */
internal val BytecodePatchContext.hideSubscribedChannelsBarConstructorLegacyMethodMatch by composingFirstMethod {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR)
    instructions(
        ResourceType.ID("parent_container"),
        afterAtMost(3, Opcode.MOVE_RESULT_OBJECT()),
        afterAtMost(
            5,
            allOf(Opcode.NEW_INSTANCE(), type($$"Landroid/widget/LinearLayout$LayoutParams;"))
        )
    )
}

internal val BytecodePatchContext.hideSubscribedChannelsBarLandscapeMethodMatch by getting {
    firstMethodComposite {
        returnType("V")
        parameterTypes()
        instructions(
            ResourceType.DIMEN("parent_view_width_in_wide_mode"),
            allOf(Opcode.INVOKE_VIRTUAL(), method("getDimensionPixelSize")),
            after(Opcode.MOVE_RESULT())
        )
    }
} using { hideSubscribedChannelsBarConstructorMethodMatch.immutableMethod }

internal val BytecodePatchContext.hideSubscribedChannelsBarLandscapeLegacyMethodMatch by getting {
    firstMethodComposite {
        returnType("V")
        parameterTypes()
        instructions(
            ResourceType.DIMEN("parent_view_width_in_wide_mode"),
            allOf(Opcode.INVOKE_VIRTUAL(), method("getDimensionPixelSize")),
            after(Opcode.MOVE_RESULT())
        )
    }
} using { hideSubscribedChannelsBarConstructorLegacyMethodMatch.immutableMethod }

internal val BytecodePatchContext.parseElementFromBufferMethodMatch by composingFirstMethod {
    parameterTypes("L", "L", "[B", "L", "L")
    instructions(
        Opcode.IGET_OBJECT(),
        // IGET_BOOLEAN // 20.07+
        afterAtMost(1, Opcode.INVOKE_INTERFACE()),
        after(Opcode.MOVE_RESULT_OBJECT()),
        "Failed to parse Element"(String::startsWith),
        allOf(
            Opcode.INVOKE_STATIC(),
            method {
                returnType.startsWith("L") && parameterTypes.size == 1
                        && parameterTypes[0].startsWith("L")
            }
        ),
        afterAtMost(4, Opcode.RETURN_OBJECT())
    )
}

internal val BytecodePatchContext.showWatermarkMethod by getting {
    firstMethodDeclaratively {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
        returnType("V")
        parameterTypes("L", "L")
    }
} using {
    firstImmutableMethodDeclaratively {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
        returnType("L")
        instructions(
            "player_overlay_in_video_programming"(),
        )
    }
}

/**
 * Matches same method as [wideSearchbarLayoutMethod].
 */
internal val BytecodePatchContext.yoodlesImageViewMethod by gettingFirstMethodDeclaratively {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("Landroid/view/View;")
    parameterTypes("L", "L")
    instructions(ResourceType.ID("youtube_logo"))
}

internal val BytecodePatchContext.albumCardsMethodMatch by composingFirstMethod {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR)
    instructions(
        ResourceType.LAYOUT("album_card"),
        afterAtMost(
            5,
            allOf(
                Opcode.INVOKE_VIRTUAL(),
                method { name == "inflate" && returnType == "Landroid/view/View;" }
            )
        ),
        after(Opcode.MOVE_RESULT_OBJECT()),
    )
}

internal val BytecodePatchContext.crowdfundingBoxMethodMatch by composingFirstMethod {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR)
    instructions(
        ResourceType.LAYOUT("donation_companion"),
        allOf(
            Opcode.INVOKE_VIRTUAL(),
            method { name == "inflate" && returnType == "Landroid/view/View;" }
        ),
        after(Opcode.MOVE_RESULT_OBJECT()),
    )
}
internal val BytecodePatchContext.filterBarHeightMethodMatch by composingFirstMethod {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR)
    instructions(
        ResourceType.DIMEN("filter_bar_height"),
        allOf(
            Opcode.INVOKE_VIRTUAL(),
            method { name == "getDimensionPixelSize" && returnType == "I" }
        ),
        after(Opcode.MOVE_RESULT()),
    )
}

/**
 * 20.10+
 */
internal fun BytecodePatchContext.getRelatedChipCloudMethodMatch() = firstMethodComposite {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR)
    instructions(
        ResourceType.ID("related_chip_cloud"),
        allOf(Opcode.INVOKE_VIRTUAL(), method { name == "findViewById" }),
        45682279L(),
        allOf(
            Opcode.INVOKE_VIRTUAL(),
            method { name == "getDimensionPixelSize" && returnType == "I" }
        ),
        after(Opcode.MOVE_RESULT()),
    )
}

internal val BytecodePatchContext.searchResultsChipBarMethodMatch by composingFirstMethod {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR)
    instructions(
        ResourceType.DIMEN("bar_container_height"),
        allOf(
            Opcode.INVOKE_VIRTUAL(),
            method { name == "getDimensionPixelSize" && returnType == "I" }),
        after(Opcode.MOVE_RESULT()),
    )
}

/**
 * 21.11+
 */
internal val BytecodePatchContext.showFloatingMicrophoneButtonMethodMatch by getting {
    firstMethodComposite {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
        returnType("V")
        parameterTypes(
            "Landroid/view/View;",
            "Lcom/google/android/libraries/quantum/fab/FloatingActionButton;",
            "Landroid/view/ViewStub;"
        )
        instructions(Opcode.IGET_BOOLEAN())
    }
} using {
    firstImmutableMethodDeclaratively(
        "Current FAB View Wrapper does not support this operation. Text: ",
    ) {
        accessFlags(AccessFlags.PRIVATE, AccessFlags.FINAL)
        returnType("V")
        parameterTypes("Z")
        custom { $$"Landroid/view/View$OnClickListener;" !in immutableClassDef.interfaces }
    }
}

/**
 * ~ 21.10
 */
internal val BytecodePatchContext.showFloatingMicrophoneButtonLegacyMethod by composingFirstMethod {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")
    parameterTypes()
    instructions(
        ResourceType.ID("fab"),
        afterAtMost(10, allOf(Opcode.CHECK_CAST(), type("/FloatingActionButton;"))),
        afterAtMost(15, Opcode.IGET_BOOLEAN()),
    )
}


internal val BytecodePatchContext.hideViewCountMethodMatch by composingFirstMethod(
    "Has attachmentRuns but drawableRequester is missing.",
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
    returnType("Ljava/lang/CharSequence;")
    opcodes(
        Opcode.RETURN_OBJECT,
        Opcode.CONST_STRING,
        Opcode.RETURN_OBJECT,
    )
}

internal val BytecodePatchContext.searchBoxTypingStringMethodMatch by composingFirstMethod {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")
    parameterTypes("L")
    instructions(
        allOf(Opcode.IGET_OBJECT(), field { type == "Ljava/util/Collection;" }),
        afterAtMost(
            5,
            method { toString() == "Ljava/util/ArrayList;-><init>(Ljava/util/Collection;)V" }),
        allOf(Opcode.IGET_OBJECT(), field { type == "Ljava/lang/String;" }),
        afterAtMost(5, method { toString() == "Ljava/lang/String;->isEmpty()Z" }),
        ResourceType.DIMEN("suggestion_category_divider_height")
    )
}

internal val BytecodePatchContext.searchSuggestionEndpointMethodMatch by getting {
    firstMethodComposite {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
        returnType("Z")
        parameterTypes()

        var methodDefiningClass = ""
        custom {
            methodDefiningClass = definingClass
            true
        }

        instructions(
            allOf(
                Opcode.IGET_OBJECT(),
                field { definingClass == methodDefiningClass && type == "Ljava/lang/String;" }),
            allOf(
                Opcode.INVOKE_STATIC(),
                method { toString() == "Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z" }),
        )
    }
} using {
    firstImmutableMethodDeclaratively("\u2026 ") {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR)
        returnType("V")
    }
}

internal val BytecodePatchContext.latestVideosContentPillMethodMatch by composingFirstMethod {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")
    parameterTypes("L", "Z")
    instructions(
        ResourceType.LAYOUT("content_pill"),
        method {
            toString() == "Landroid/view/LayoutInflater;->inflate(ILandroid/view/ViewGroup;Z)Landroid/view/View;"
        },
        after(Opcode.MOVE_RESULT_OBJECT())
    )
}

internal val BytecodePatchContext.latestVideosBarMethodMatch by composingFirstMethod {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")
    parameterTypes("L", "Z")
    instructions(
        ResourceType.LAYOUT("bar"),
        method {
            toString() == "Landroid/view/LayoutInflater;->inflate(ILandroid/view/ViewGroup;Z)Landroid/view/View;"
        },
        after(Opcode.MOVE_RESULT_OBJECT())
    )
}


internal val BytecodePatchContext.bottomSheetMenuItemBuilderMethodMatch by composingFirstMethod {
    returnType("L")
    parameterTypes("L")
    instructions(
        allOf(
            Opcode.INVOKE_STATIC(),
            method {
                returnType == "Ljava/lang/CharSequence;" &&
                        parameterTypes.size == 1 && parameterTypes[0].startsWith("L")
            }
        ),
        after(Opcode.MOVE_RESULT_OBJECT()),
        "Text missing for BottomSheetMenuItem."()
    )
}

internal val BytecodePatchContext.contextualMenuItemBuilderMethodMatch by composingFirstMethod {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL, AccessFlags.SYNTHETIC)
    returnType("V")
    parameterTypes("L", "L")
    instructions(
        allOf(Opcode.CHECK_CAST(), type("Landroid/widget/TextView;")),
        afterAtMost(
            5,
            method { toString() == "Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V" }
        ),
        ResourceType.DIMEN("poster_art_width_default"),
    )
}

internal val BytecodePatchContext.channelTabBuilderMethod by gettingFirstMethodDeclaratively {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("Landroid/view/View;")
    parameterTypes(
        "Ljava/lang/CharSequence;",
        "Ljava/lang/CharSequence;",
        "Z",
        "L"
    )
}

internal val BytecodePatchContext.channelTabRendererMethod by gettingFirstMethodDeclaratively(
    "TabRenderer.content contains SectionListRenderer but the tab does not have a section list controller."
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")
    parameterTypes(
        "L",
        "Ljava/util/List;",
        "I"
    )
}

internal val BytecodePatchContext.engagementPanelInformationButtonMethodMatch by composingFirstMethod {
    parameterTypes("Landroid/content/Context;")
    instructions(
        ResourceType.ID("information_button"),
        Opcode.CHECK_CAST(),
    )
}

internal val BytecodePatchContext.createSearchSuggestionsMethodMatch by composingFirstMethod(
    "ss_rds",
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")
    parameterTypes("L", "I")
    instructions(
        method("next"),
        afterAtMost(30, 0L()),
        afterAtMost(10, method("setVisibility")),
        afterAtMost(10, 8L()),
        afterAtMost(10, method("setVisibility")),
        method("setImageDrawable"),
        method("parse"),
        afterAtMost(20, 0L()),
    )
}