package app.revanced.patches.brave.premium

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.Opcode

@Suppress("unused")
val removeSettingsPromoCardFlickerPatch =
    bytecodePatch(
        name = "Remove Settings Promo Card Flicker",
        description = "Removes the 500ms flicker of the sign-in promo banner in settings.",
    ) {
        compatibleWith("com.brave.browser")

        dependsOn(unlockOriginPatch)

        apply {
            // Fix the 500ms flicker for SettingsPromoCardPreference (Sign in to sync banner).
            val promoCardClassDef = settingsPromoCardPreferenceClassDef

            if (promoCardClassDef != null) {
                val promoCardClass = classDefs.getOrReplaceMutable(promoCardClassDef)

                val promoInit = promoCardClass.promoInitMethod
                val promoBindMethod = promoCardClass.getPromoBindMethod()

                // Dynamically find the obfuscated setVisible(boolean) method name from onBindViewHolder.
                val setVisibleInstructionIndex =
                    promoBindMethod.indexOfFirstInstruction {
                        opcode == Opcode.INVOKE_VIRTUAL &&
                            methodReference?.definingClass == "Landroidx/preference/Preference;" &&
                            methodReference?.returnType == "V" &&
                            methodReference?.parameterTypes == listOf("Z")
                    }
                val setVisibleMethodName =
                    promoBindMethod
                        .getInstruction(setVisibleInstructionIndex)
                        .methodReference!!
                        .name

                if (promoInit != null) {
                    promoInit.addInstructions(
                        1,
                        """
                            const/4 p1, 0x0
                            invoke-virtual {p0, p1}, Landroidx/preference/Preference;->$setVisibleMethodName(Z)V
                        """,
                    )
                }
            }
        }
    }
