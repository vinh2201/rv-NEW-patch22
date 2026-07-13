package app.revanced.patches.instagram.misc.download

import app.revanced.patcher.accessFlags
import app.revanced.patcher.classDef
import app.revanced.patcher.custom
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.firstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import app.revanced.patches.instagram.misc.extension.sharedExtensionPatch
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/instagram/download/DownloadMediaPatch;"

private const val MEDIA_TYPE = "Lcom/instagram/feed/media/Media;"
private const val ACTIVITY_TYPE = "Landroidx/fragment/app/FragmentActivity;"

@Suppress("unused")
val downloadMediaPatch = bytecodePatch(
    name = "Download media",
    description = "Adds a \"Download\" option to the post \"...\" menu to save photos and videos.",
    use = false,
) {
    compatibleWith("com.instagram.android"("425.0.0.47.61"))

    dependsOn(sharedExtensionPatch)

    apply {
        // add-row: static A00(style, MediaOption$Option, creator, CharSequence label, ArrayList list, boolean).
        val menuCreatorClass = mediaOptionsMenuCreatorAnchor.classDef.type
        val addOptionRowMethod = firstMethodDeclaratively {
            definingClass(menuCreatorClass)
            accessFlags(AccessFlags.STATIC)
            returnType("V")
            custom {
                val types = parameterTypes.map { it.toString() }
                types.size == 6 &&
                    types[1] == MEDIA_OPTION &&
                    types[3] == "Ljava/lang/CharSequence;" &&
                    types[4] == "Ljava/util/ArrayList;" &&
                    types[5] == "Z"
            }
        }

        val addRowMethodReference =
            "$menuCreatorClass->${addOptionRowMethod.name}(" +
                addOptionRowMethod.parameterTypes.joinToString("") + ")V"

        // Append a download row here, guarded to once per menu build. p2 = creator, p4 = the row list.
        addOptionRowMethod.apply {
            val returnIndex = indexOfFirstInstructionOrThrow(Opcode.RETURN_VOID)

            addInstructionsWithLabels(
                returnIndex,
                """
                    invoke-static { p4 }, $EXTENSION_CLASS_DESCRIPTOR->shouldAddDownloadRow(Ljava/util/List;)Z
                    move-result v0
                    if-eqz v0, :ig_dl_skip
                    const/4 p0, 0x0
                    sget-object p1, $MEDIA_OPTION->DOWNLOAD:$MEDIA_OPTION
                    const-string p3, "Download"
                    const/4 p5, 0x0
                    invoke-static/range { p0 .. p5 }, $addRowMethodReference
                """,
                ExternalLabel("ig_dl_skip", getInstruction(returnIndex)),
            )
        }

        // Overflow helper: holds the tapped media/activity/carousel position, dispatches option clicks.
        val overflowHelperClassDef = mediaOptionsOverflowHelperAnchor.classDef
        val overflowHelperClass = overflowHelperClassDef.type

        val mediaField = overflowHelperClassDef.fields.first { it.type == MEDIA_TYPE }.name
        val activityField = overflowHelperClassDef.fields.first { it.type == ACTIVITY_TYPE }.name
        // Two int fields hold carousel positions; the extension picks whichever is in range.
        val intFields = overflowHelperClassDef.fields.filter { it.type == "I" }.map { it.name }
        val indexFieldA = intFields[0]
        val indexFieldB = intFields[1]

        // Click dispatcher: public final void(MediaOption$Option).
        val optionClickMethod = firstMethodDeclaratively {
            definingClass(overflowHelperClass)
            accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
            returnType("V")
            custom {
                parameterTypes.size == 1 && parameterTypes[0].toString() == MEDIA_OPTION
            }
        }

        // p0/p1 are high registers here, so move them low before if-ne/iget (4-bit encoded).
        optionClickMethod.apply {
            addInstructionsWithLabels(
                0,
                """
                    move-object/from16 v3, p1
                    sget-object v0, $MEDIA_OPTION->DOWNLOAD:$MEDIA_OPTION
                    if-ne v3, v0, :ig_dl_continue
                    move-object/from16 v3, p0
                    iget-object v0, v3, $overflowHelperClass->$mediaField:$MEDIA_TYPE
                    iget-object v1, v3, $overflowHelperClass->$activityField:$ACTIVITY_TYPE
                    iget v2, v3, $overflowHelperClass->$indexFieldA:I
                    iget v4, v3, $overflowHelperClass->$indexFieldB:I
                    invoke-static { v0, v1, v2, v4 }, $EXTENSION_CLASS_DESCRIPTOR->onPostDownloadClick(Ljava/lang/Object;Ljava/lang/Object;II)V
                    return-void
                """,
                ExternalLabel("ig_dl_continue", getInstruction(0)),
            )
        }

        // Story menu: one builder returns the CharSequence[] shown in both the AlertDialog and bottom sheet.
        // The story helper class is param[2] of this dialog builder, matched by its all-readable signature.
        val storyDialogMethod = firstMethodDeclaratively {
            accessFlags(AccessFlags.STATIC)
            returnType("Landroid/app/Dialog;")
            custom {
                val types = parameterTypes.map { it.toString() }
                types.size == 4 &&
                    types[0] == "Landroid/content/DialogInterface\$OnClickListener;" &&
                    types[1] == "Landroid/content/DialogInterface\$OnDismissListener;" &&
                    types[3] == "[Ljava/lang/CharSequence;"
            }
        }
        val storyHelperClass = storyDialogMethod.parameterTypes[2].toString()

        // Append "Download" to the options array.
        val storyOptionsMethod = firstMethodDeclaratively {
            definingClass(storyHelperClass)
            accessFlags(AccessFlags.STATIC)
            returnType("[Ljava/lang/CharSequence;")
            custom { parameterTypes.size == 1 }
        }
        storyOptionsMethod.apply {
            val returnIndex = indexOfFirstInstructionOrThrow(Opcode.RETURN_OBJECT)
            val register = getInstruction<OneRegisterInstruction>(returnIndex).registerA
            addInstructions(
                returnIndex,
                """
                    invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->appendStoryDownloadLabel([Ljava/lang/CharSequence;)[Ljava/lang/CharSequence;
                    move-result-object v$register
                """,
            )
        }

        // Dialog click dispatcher static void(helper, selectedLabel).
        val storyClickMethod = firstMethodDeclaratively {
            definingClass(storyHelperClass)
            accessFlags(AccessFlags.STATIC)
            returnType("V")
            custom {
                val types = parameterTypes.map { it.toString() }
                types.size == 2 && types[0] == storyHelperClass && types[1] == "Ljava/lang/String;"
            }
        }
        storyClickMethod.apply {
            addInstructionsWithLabels(
                0,
                """
                    const-string v0, "Download"
                    invoke-virtual { p1, v0 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                    move-result v0
                    if-eqz v0, :ig_dl_story_continue
                    invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->onStoryDownloadClick(Ljava/lang/Object;)V
                    return-void
                """,
                ExternalLabel("ig_dl_story_continue", getInstruction(0)),
            )
        }

        // Besides the AlertDialog dispatcher above, the bottom-sheet / context-menu story variants
        // dispatch a row tap through a static handler whose last parameter is the selected CharSequence
        // label and which also takes the helper. The helper class has SEVERAL such handlers (different
        // menu variants), so hook every one.
        storyOptionsMethod.classDef.methods
            .filter { method ->
                (method.accessFlags and AccessFlags.STATIC.value) != 0 &&
                    method.returnType == "V" &&
                    method.parameterTypes.size > 2 &&
                    method.parameterTypes.last().toString() == "Ljava/lang/CharSequence;" &&
                    method.parameterTypes.any { it.toString() == storyHelperClass }
            }
            .map { it.name }
            .forEach { dispatchName ->
                firstMethodDeclaratively {
                    definingClass(storyHelperClass)
                    name(dispatchName)
                    accessFlags(AccessFlags.STATIC)
                    returnType("V")
                    custom { parameterTypes.last().toString() == "Ljava/lang/CharSequence;" }
                }.apply {
                    val types = parameterTypes.map { it.toString() }
                    val helperParam = "p${types.indexOf(storyHelperClass)}"
                    val labelParam = "p${types.size - 1}"
                    addInstructionsWithLabels(
                        0,
                        """
                            move-object/from16 v0, $labelParam
                            const-string v1, "Download"
                            invoke-virtual { v1, v0 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                            move-result v1
                            if-eqz v1, :ig_dl_sheet_$dispatchName
                            move-object/from16 v0, $helperParam
                            invoke-static { v0 }, $EXTENSION_CLASS_DESCRIPTOR->onStoryDownloadClick(Ljava/lang/Object;)V
                            return-void
                        """,
                        ExternalLabel("ig_dl_sheet_$dispatchName", getInstruction(0)),
                    )
                }
            }

        // Reels menu: hook the sheet-show method (has the reel media + finished options config) and append
        // our own Download row + listener
        val clipsHelperClassDef = clipsMoreOptionsAnchor.classDef
        val clipsHelperClass = clipsHelperClassDef.type

        // Sheet-show method (View first) — it reads the config's accumulated row list and displays it.
        // The config (param 1) holds the row-adders; trailing params vary, so match on View-first only.
        val clipsShowMethod = firstMethodDeclaratively {
            definingClass(clipsHelperClass)
            accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
            returnType("V")
            custom {
                val types = parameterTypes.map { it.toString() }
                types.size >= 2 && types[0] == "Landroid/view/View;"
            }
        }
        val optionsConfigClass = clipsShowMethod.parameterTypes[1].toString()
        val clipsMediaField = clipsHelperClassDef.fields.first { it.type == MEDIA_TYPE }.name
        val clipsActivityField = clipsHelperClassDef.fields.first { it.type == ACTIVITY_TYPE }.name

        // Two identical row-adders differ only by the destructive/"red" boolean they forward on:
        // the destructive one loads it as const 1, the normal one as const 0. Pick the normal one.
        fun Method.isRowAdder(): Boolean {
            val types = parameterTypes.map { it.toString() }
            return returnType == "V" &&
                (accessFlags and AccessFlags.STATIC.value) == 0 &&
                types.size == 4 &&
                types[0] == "Landroid/content/Context;" &&
                types[1] == "Landroid/view/View\$OnClickListener;" &&
                types[2] == "Ljava/lang/String;" &&
                types[3] == "I"
        }
        val addRowMethod = firstMethodDeclaratively {
            definingClass(optionsConfigClass)
            custom { isRowAdder() }
        }.classDef.methods
            .filter { it.isRowAdder() }
            .minByOrNull { method ->
                method.implementation!!.instructions.count {
                    it.opcode == Opcode.CONST_4 && (it as NarrowLiteralInstruction).narrowLiteral == 1
                }
            }!!
        val addRowReference =
            "$optionsConfigClass->${addRowMethod.name}" +
                "(Landroid/content/Context;Landroid/view/View\$OnClickListener;Ljava/lang/String;I)V"

        clipsShowMethod.apply {
            // The show method has trailing params, so p0 (helper) and p2 (config) are >v15 — move low first.
            addInstructions(
                0,
                """
                    move-object/from16 v4, p0
                    move-object/from16 v5, p2
                    sget-object v0, $MEDIA_OPTION->DOWNLOAD:$MEDIA_OPTION
                    invoke-virtual { v0 }, $MEDIA_OPTION->getIconDrawable()I
                    move-result v1
                    iget-object v0, v4, $clipsHelperClass->$clipsMediaField:$MEDIA_TYPE
                    invoke-static { v0 }, $EXTENSION_CLASS_DESCRIPTOR->createReelDownloadListener(Ljava/lang/Object;)Landroid/view/View${'$'}OnClickListener;
                    move-result-object v0
                    iget-object v2, v4, $clipsHelperClass->$clipsActivityField:$ACTIVITY_TYPE
                    const-string v3, "Download"
                    invoke-virtual { v5, v2, v0, v3, v1 }, $addRowReference
                """,
            )
        }
    }
}
