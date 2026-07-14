package app.revanced.patches.instagram.misc.download

import app.revanced.patcher.classDef
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.instagram.misc.extension.sharedExtensionPatch
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/instagram/download/DownloadMediaPatch;"

private const val MEDIA_CLASS_DESCRIPTOR = "Lcom/instagram/feed/media/Media;"
private const val ACTIVITY_CLASS_DESCRIPTOR = "Landroidx/fragment/app/FragmentActivity;"

private val Method.descriptor
    get() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

@Suppress("unused")
val downloadMediaPatch = bytecodePatch(
    name = "Download media",
    description = "Adds a \"Download\" option to the post \"...\" menu to save photos and videos.",
    use = false,
) {
    compatibleWith("com.instagram.android"("425.0.0.47.61"))

    dependsOn(sharedExtensionPatch)

    apply {
        // Append a download row once per menu build. p2 = creator, p4 = the row list.
        addOptionRowMethod.apply {
            val returnIndex = indexOfFirstInstructionOrThrow(Opcode.RETURN_VOID)
            addInstructionsWithLabels(
                returnIndex,
                """
                    invoke-static { p4 }, $EXTENSION_CLASS_DESCRIPTOR->shouldAddDownloadRow(Ljava/util/List;)Z
                    move-result v0
                    if-eqz v0, :ig_dl_skip
                    const/4 p0, 0x0
                    sget-object p1, $MEDIA_OPTION_CLASS_DESCRIPTOR->DOWNLOAD:$MEDIA_OPTION_CLASS_DESCRIPTOR
                    const-string p3, "Download"
                    const/4 p5, 0x0
                    invoke-static/range { p0 .. p5 }, $descriptor
                """,
                ExternalLabel("ig_dl_skip", getInstruction(returnIndex)),
            )
        }

        // Post overflow helper holds the tapped media/activity and two carousel-position int fields.
        val overflowHelperClassDef = navigateToCameraMethod.classDef
        val mediaField = overflowHelperClassDef.fields.first { it.type == MEDIA_CLASS_DESCRIPTOR }
        val activityField = overflowHelperClassDef.fields.first { it.type == ACTIVITY_CLASS_DESCRIPTOR }
        val (indexFieldA, indexFieldB) = overflowHelperClassDef.fields.filter { it.type == "I" }

        // p0/p1 are high registers here, so move them low before if-ne/iget (4-bit encoded).
        postOptionClickMethod.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v3, p1
                sget-object v0, $MEDIA_OPTION_CLASS_DESCRIPTOR->DOWNLOAD:$MEDIA_OPTION_CLASS_DESCRIPTOR
                if-ne v3, v0, :ig_dl_continue
                move-object/from16 v3, p0
                iget-object v0, v3, $mediaField
                iget-object v1, v3, $activityField
                iget v2, v3, $indexFieldA
                iget v4, v3, $indexFieldB
                invoke-static { v0, v1, v2, v4 }, $EXTENSION_CLASS_DESCRIPTOR->onPostDownloadClick(Ljava/lang/Object;Ljava/lang/Object;II)V
                return-void
            """,
            ExternalLabel("ig_dl_continue", postOptionClickMethod.getInstruction(0)),
        )

        // The story dialog builder's 3rd parameter type is the story helper class.
        val storyHelperClass = storyDialogMethod.parameterTypes[2].toString()

        storyOptionsMethod(storyHelperClass).apply {
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

        val storyClickMethod = storyOptionClickMethod(storyHelperClass)
        storyClickMethod.addInstructionsWithLabels(
            0,
            """
                const-string v0, "Download"
                invoke-virtual { p1, v0 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :ig_dl_story_continue
                invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->onStoryDownloadClick(Ljava/lang/Object;)V
                return-void
            """,
            ExternalLabel("ig_dl_story_continue", storyClickMethod.getInstruction(0)),
        )

        // Story bottom-sheet/context-menu variants each have their own dispatcher, hook every one.
        storyOptionsMethod(storyHelperClass).classDef.methods
            .filter { method ->
                method.accessFlags and AccessFlags.STATIC.value != 0 &&
                    method.returnType == "V" &&
                    method.parameterTypes.size > 2 &&
                    method.parameterTypes.last() == "Ljava/lang/CharSequence;" &&
                    method.parameterTypes.any { it == storyHelperClass }
            }
            .map { it.name }
            .forEach { dispatchName ->
                val dispatchMethod = storyOptionDispatchMethod(storyHelperClass, dispatchName)
                val helperParam = "p${dispatchMethod.parameterTypes.indexOf(storyHelperClass)}"
                val labelParam = "p${dispatchMethod.parameterTypes.size - 1}"
                dispatchMethod.addInstructionsWithLabels(
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
                    ExternalLabel("ig_dl_sheet_$dispatchName", dispatchMethod.getInstruction(0)),
                )
            }

        // Reels menu: append our own Download row + listener to the sheet the show method displays.
        val clipsHelperClassDef = clipsOrganicMoreOptionsMethod.classDef
        val clipsMediaField = clipsHelperClassDef.fields.first { it.type == MEDIA_CLASS_DESCRIPTOR }
        val clipsActivityField = clipsHelperClassDef.fields.first { it.type == ACTIVITY_CLASS_DESCRIPTOR }
        val optionsConfigClass = clipsShowMethod.parameterTypes[1].toString()

        // Two row-adders differ only by the destructive/"red" boolean (const 1 vs 0), pick the normal one.
        val clipsRowAdder = clipsRowAdderMethod(optionsConfigClass).classDef.methods
            .filter { it.isClipsRowAdder() }
            .minByOrNull { method ->
                method.implementation!!.instructions.count {
                    it.opcode == Opcode.CONST_4 && (it as NarrowLiteralInstruction).narrowLiteral == 1
                }
            }!!

        // The show method has trailing params, so p0 (helper) and p2 (config) are >v15 — move low first.
        clipsShowMethod.addInstructions(
            0,
            """
                move-object/from16 v4, p0
                move-object/from16 v5, p2
                sget-object v0, $MEDIA_OPTION_CLASS_DESCRIPTOR->DOWNLOAD:$MEDIA_OPTION_CLASS_DESCRIPTOR
                invoke-virtual { v0 }, $MEDIA_OPTION_CLASS_DESCRIPTOR->getIconDrawable()I
                move-result v1
                iget-object v0, v4, $clipsMediaField
                invoke-static { v0 }, $EXTENSION_CLASS_DESCRIPTOR->createReelDownloadListener(Ljava/lang/Object;)Landroid/view/View${'$'}OnClickListener;
                move-result-object v0
                iget-object v2, v4, $clipsActivityField
                const-string v3, "Download"
                invoke-virtual { v5, v2, v0, v3, v1 }, ${clipsRowAdder.descriptor}
            """,
        )
    }
}
