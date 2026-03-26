package app.revanced.patches.youtube.video.speed.custom

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableField
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableField.Companion.toMutable
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod.Companion.toMutable
import app.revanced.patcher.classDef
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.instructions
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.immutableClassDef
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.all.misc.resources.addResources
import app.revanced.patches.all.misc.resources.addResourcesPatch
import app.revanced.patches.shared.misc.mapping.resourceMappingPatch
import app.revanced.patches.shared.misc.settings.preference.InputType
import app.revanced.patches.shared.misc.settings.preference.SwitchPreference
import app.revanced.patches.shared.misc.settings.preference.TextPreference
import app.revanced.patches.shared.misc.litho.filter.addLithoFilter
import app.revanced.patches.youtube.misc.extension.sharedExtensionPatch
import app.revanced.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.revanced.patches.youtube.misc.playservice.is_19_47_or_greater
import app.revanced.patches.youtube.misc.playservice.is_20_34_or_greater
import app.revanced.patches.youtube.misc.playservice.is_21_02_or_greater
import app.revanced.patches.youtube.misc.playservice.is_21_12_or_greater
import app.revanced.patches.youtube.misc.playservice.versionCheckPatch
import app.revanced.patches.youtube.misc.recyclerviewtree.hook.addRecyclerViewTreeHook
import app.revanced.patches.youtube.misc.recyclerviewtree.hook.recyclerViewTreeHookPatch
import app.revanced.patches.youtube.misc.settings.settingsPatch
import app.revanced.patches.youtube.shared.playbackSpeedOnItemClickParentMethodMatch
import app.revanced.patches.youtube.video.speed.settingsMenuVideoSpeedGroup
import app.revanced.util.addInstructionsAtControlFlowLabel
import app.revanced.util.indexOfFirstLiteralInstructionOrThrow
import app.revanced.util.insertLiteralOverride
import app.revanced.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val FILTER_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/youtube/patches/litho/PlaybackSpeedMenuFilter;"

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/youtube/patches/playback/speed/CustomPlaybackSpeedPatch;"

internal val customPlaybackSpeedPatch = bytecodePatch(
    description = "Adds custom playback speed options.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
        lithoFilterPatch,
        versionCheckPatch,
        recyclerViewTreeHookPatch,
        resourceMappingPatch,
    )

    apply {
        addResources("youtube", "video.speed.custom.customPlaybackSpeedPatch")

        settingsMenuVideoSpeedGroup.addAll(
            listOf(
                SwitchPreference("revanced_custom_speed_menu"),
                SwitchPreference("revanced_restore_old_speed_menu"),
                TextPreference(
                    "revanced_custom_playback_speeds",
                    inputType = InputType.TEXT_MULTI_LINE,
                ),
            ),
        )

        if (is_19_47_or_greater) {
            settingsMenuVideoSpeedGroup.add(
                TextPreference("revanced_speed_tap_and_hold", inputType = InputType.NUMBER_DECIMAL),
            )
        }

        // Override the min/max speeds that can be used.
        (if (is_20_34_or_greater) speedLimiterMethod else speedLimiterLegacyMethod).apply {
            val limitMinIndex = indexOfFirstLiteralInstructionOrThrow(0.25f)
            // Older unsupported targets use 2.0f and not 4.0f
            val limitMaxIndex = indexOfFirstLiteralInstructionOrThrow(4.0f)

            val limitMinRegister = getInstruction<OneRegisterInstruction>(limitMinIndex).registerA
            val limitMaxRegister = getInstruction<OneRegisterInstruction>(limitMaxIndex).registerA
            replaceInstruction(limitMinIndex, "const/high16 v$limitMinRegister, 0.0f")
            replaceInstruction(limitMaxIndex, "const/high16 v$limitMaxRegister, 8.0f")
        }

        // Turn off client side flag that use server provided min/max speeds.
        if (is_20_34_or_greater) {
            serverSideMaxSpeedFeatureFlagMethod.returnEarly(false)
        }

        // region Force old playback speed menu.

        // Replace the speeds float array with custom speeds.
        speedArrayGeneratorMethodMatch.let {
            it.method.apply {
                val playbackSpeedsArrayType = "$EXTENSION_CLASS_DESCRIPTOR->customPlaybackSpeeds:[F"
                // Apply changes from last index to first to preserve indexes.

                val originalArrayFetchIndex = it[5]
                val originalArrayFetchDestination =
                    getInstruction<OneRegisterInstruction>(it[5]).registerA
                replaceInstruction(
                    originalArrayFetchIndex,
                    "sget-object v$originalArrayFetchDestination, $playbackSpeedsArrayType",
                )

                val arrayLengthConstDestination =
                    getInstruction<OneRegisterInstruction>(it[3]).registerA
                val newArrayIndex = it[4]
                addInstructions(
                    newArrayIndex,
                    """
                        sget-object v$arrayLengthConstDestination, $playbackSpeedsArrayType
                        array-length v$arrayLengthConstDestination, v$arrayLengthConstDestination
                    """,
                )

                val sizeCallIndex = it[0] + 1
                val sizeCallResultRegister =
                    getInstruction<OneRegisterInstruction>(sizeCallIndex).registerA
                replaceInstruction(sizeCallIndex, "const/4 v$sizeCallResultRegister, 0x0")
            }
        }

        // Add a static INSTANCE field to the class.
        // This is later used to call "showOldPlaybackSpeedMenu" on the instance.

        val instanceField = ImmutableField(
            getOldPlaybackSpeedsMethod.immutableClassDef.type,
            "INSTANCE",
            getOldPlaybackSpeedsMethod.immutableClassDef.type,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            null,
        ).toMutable()

        getOldPlaybackSpeedsMethod.classDef.staticFields.add(instanceField)
        // Set the INSTANCE field to the instance of the class.
        // In order to prevent a conflict with another patch, add the instruction at index 1.
        getOldPlaybackSpeedsMethod.addInstruction(1, "sput-object p0, $instanceField")

        // Get the "showOldPlaybackSpeedMenu" method.
        // This is later called on the field INSTANCE.
        val showOldPlaybackSpeedMenuMethodMatch =
            getOldPlaybackSpeedsMethod.immutableClassDef.showOldPlaybackSpeedMenuMethodMatch

        // Insert the call to the "showOldPlaybackSpeedMenu" method on the field INSTANCE.
        showOldPlaybackSpeedMenuExtensionMethod.apply {
            addInstructionsWithLabels(
                instructions.lastIndex,
                """
                    sget-object v0, $instanceField
                    if-nez v0, :not_null
                    return-void
                    :not_null
                    invoke-virtual { v0 }, ${showOldPlaybackSpeedMenuMethodMatch.method}
                """,
            )
        }

        // endregion

        // Fix restoring old playback speed menu.
        if (is_21_12_or_greater) {
            val onItemClickClass: String
            val fragmentIdField: MutableField
            val fragmentManagerMethod: MethodReference
            playbackSpeedOnItemClickParentMethodMatch.let {
                it.method.apply {
                    onItemClickClass = definingClass
                    fragmentManagerMethod = getInstruction(it[0]).methodReference!!

                    // Add a fragment id instance field to the class.
                    fragmentIdField = ImmutableField(
                        definingClass,
                        "INSTANCE",
                        "Ljava/lang/String;",
                        AccessFlags.PUBLIC.value,
                        null,
                        null,
                        null,
                    ).toMutable().also(it.classDef.instanceFields::add)

                    addInstruction(
                        instructions.lastIndex,
                        "iput-object p1, p0, $fragmentIdField"
                    )

                }
            }

            val bottomSheetAvailabilityPrimaryMethodCall: String
            val bottomSheetAvailabilitySecondaryMethodCall: String
            val bottomSheetBuilderMethodCall: String

            audioTrackOldBottomSheetMethodMatch.let {
                fun getMethodCall(matchIndex: Int): String {
                    val methodReference =
                        it.method.getInstruction(it[matchIndex]).methodReference!!

                    return methodReference.toString()
                        .replace(methodReference.definingClass, onItemClickClass)
                }

                bottomSheetAvailabilityPrimaryMethodCall = getMethodCall(0)
                bottomSheetAvailabilitySecondaryMethodCall = getMethodCall(1)
                bottomSheetBuilderMethodCall = getMethodCall(3)
            }

            showOldPlaybackSpeedMenuMethodMatch.let {
                val onItemClickField = it.classDef.fields.single { field ->
                    field.type == onItemClickClass
                }
                val fragmentManagerField = it.classDef.fields.single { field ->
                    field.type == fragmentManagerMethod.definingClass
                }

                val helperMethod = ImmutableMethod(
                    it.classDef.type,
                    "patch_showOldPlaybackSpeedMenu",
                    listOf(),
                    "V",
                    AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    MutableMethodImplementation(4),
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            # Check if the bottom sheet is available.
                            iget-object v0, p0, $onItemClickField
                            invoke-virtual { v0 }, $bottomSheetAvailabilityPrimaryMethodCall
                            move-result v1
                            if-nez v1, :ignore

                            # Check if the bottom sheet is available.
                            invoke-virtual { v0 }, $bottomSheetAvailabilitySecondaryMethodCall
                            move-result v1
                            if-nez v1, :ignore

                            # Check if the fragment ID is not null.
                            iget-object v2, v0, $fragmentIdField
                            if-eqz v2, :ignore

                            # Shows the bottom sheet dialog.
                            iget-object v1, p0, $fragmentManagerField
                            invoke-virtual { v1 }, $fragmentManagerMethod
                            move-result-object v1
                            invoke-virtual { v0, v1, v2 }, $bottomSheetBuilderMethodCall

                            :ignore
                            return-void
                        """
                    )
                }.also(it.classDef.methods::add)

                it.method.apply {
                    val index = it[-1]
                    val register = getInstruction<TwoRegisterInstruction>(index).registerA

                    addInstructionsAtControlFlowLabel(
                        index,
                        """
                            invoke-static { }, ${EXTENSION_CLASS_DESCRIPTOR}->restoreOldPlaybackSpeedMenu()Z
                            move-result v$register
                            if-eqz v$register, :ignore
                            invoke-direct { p0 }, $helperMethod
                            return-void
                            :ignore
                            nop
                        """
                    )
                }
            }
        } else if (is_21_02_or_greater) {
            flyoutMenuNonLegacyFeatureFlagMethodMatch.let {
                it.method.insertLiteralOverride(
                    it[0],
                    "$EXTENSION_CLASS_DESCRIPTOR->useNewFlyoutMenu(Z)Z"
                )
            }
        }

        // Close the unpatched playback dialog and show the custom speeds.
        addRecyclerViewTreeHook(EXTENSION_CLASS_DESCRIPTOR)

        // Required to check if the playback speed menu is currently shown.
        addLithoFilter(FILTER_CLASS_DESCRIPTOR)

        // endregion

        // region Custom tap and hold 2x speed.

        if (is_19_47_or_greater) {
            // Function, because it can be the same method as getTapAndHoldHapticsMethodMatch.
            getTapAndHoldSpeedMethodMatch().let {
                it.method.apply {
                    val speedIndex = it[-1]
                    val speedRegister =
                        getInstruction<OneRegisterInstruction>(speedIndex).registerA

                    addInstructions(
                        speedIndex + 1,
                        """
                            invoke-static { }, ${EXTENSION_CLASS_DESCRIPTOR}->getTapAndHoldSpeed()F
                            move-result v$speedRegister
                        """
                    )

                    val enabledIndex = it[3]
                    val enabledRegister =
                        getInstruction<OneRegisterInstruction>(enabledIndex).registerA

                    addInstructions(
                        enabledIndex,
                        """
                            invoke-static { v$enabledRegister }, $EXTENSION_CLASS_DESCRIPTOR->disableTapAndHoldSpeed(Z)Z
                            move-result v$enabledRegister
                        """
                    )
                }
            }
        }

        // endregion
    }
}
