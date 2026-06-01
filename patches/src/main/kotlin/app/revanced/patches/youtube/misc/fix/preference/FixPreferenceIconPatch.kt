package app.revanced.patches.youtube.misc.fix.preference

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod.Companion.toMutable
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.youtube.misc.extension.sharedExtensionPatch
import app.revanced.patches.youtube.misc.playservice.is_21_14_or_greater
import app.revanced.patches.youtube.misc.playservice.versionCheckPatch
import app.revanced.util.addInstructionsAtControlFlowLabel
import app.revanced.util.findFreeRegister
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/youtube/patches/FixPreferenceIconPatch;"

internal val fixPreferenceIconPatch = bytecodePatch{
    dependsOn(
        sharedExtensionPatch,
        versionCheckPatch,
    )

    apply {
        if (!is_21_14_or_greater) {
            return@apply
        }

        val helperMethod: MutableMethod

        findPreferenceByIndexMethodMatch.let {
            val getAllPreferenceField = it.method.getInstruction<FieldReference>(it[-1])

            it.classDef.apply {
                helperMethod = ImmutableMethod(
                    type,
                    "patch_removePreferenceIcon",
                    listOf(),
                    "V",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    annotations,
                    null,
                    MutableMethodImplementation(5),
                ).toMutable().apply {
                    addInstructionsWithLabels(
                        0,
                        """
                            invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->removePreferenceIcon()Z
                            move-result v0

                            if-eqz v0, :exit
                            iget-object v0, p0, $getAllPreferenceField
                            invoke-interface { v0 }, Ljava/util/List;->iterator()Ljava/util/Iterator;
                            move-result-object v1
                            
                            :loop
                            invoke-interface { v1 }, Ljava/util/Iterator;->hasNext()Z
                            move-result v2

                            if-eqz v2, :exit
                            invoke-interface { v1 }, Ljava/util/Iterator;->next()Ljava/lang/Object;
                            move-result-object v2
                            instance-of v3, v2, Landroidx/preference/Preference;

                            if-eqz v3, :loop
                            check-cast v2, Landroidx/preference/Preference;

                            # Call setIconSpaceReserved(false).
                            const/4 v3, 0x0
                            invoke-virtual { v2, v3 }, $setPreferenceIconSpaceReservedMethod

                            # Call setIcon(null).
                            const/4 v3, 0x0
                            invoke-virtual { v2, v3 }, $setPreferenceIconMethod

                            goto :loop

                            :exit
                            return-void
                        """
                    )
                }

                methods.add(helperMethod)
            }
        }

        preferenceScreenSyntheticMethodMatch.let { match ->
            match.method.apply {
                val getPreferenceScreenIndex = match[1]
                val getPreferenceScreenRegister =
                    getInstruction<FiveRegisterInstruction>(getPreferenceScreenIndex).registerC
                val getPreferenceScreenReference =
                    getInstruction<ReferenceInstruction>(getPreferenceScreenIndex).reference

                val insertIndex = match[-1]
                val preferenceScreenRegister =
                    findFreeRegister(insertIndex, getPreferenceScreenRegister)

                addInstructionsAtControlFlowLabel(
                    insertIndex,
                    """
                        invoke-virtual { v$getPreferenceScreenRegister }, $getPreferenceScreenReference
                        move-result-object v$preferenceScreenRegister
                        
                        if-eqz v$preferenceScreenRegister, :ignore
                        
                        invoke-virtual { v$preferenceScreenRegister }, $helperMethod
                        
                        :ignore
                        nop
                    """
                )
            }
        }
    }
}
