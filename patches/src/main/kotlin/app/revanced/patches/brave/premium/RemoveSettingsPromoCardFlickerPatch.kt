package app.revanced.patches.brave.premium

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

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
            val promoCardClassDef = classDefs.getOrReplaceMutable(getSettingsPromoCardPreferenceClassDef())

            promoCardClassDef.firstMethod(promoCardClassDef.getConstructorMethod()).apply {
                addInstructions(
                    1,
                    """
                        const/4 p1, 0x0
                        invoke-virtual { p0, p1 }, Landroidx/preference/Preference;->${
                            promoCardClassDef.getPromoBindMethodMatch().let { match ->
                                val setVisibleIndex = match[0]
                                match.method
                                    .getInstruction<ReferenceInstruction>(setVisibleIndex)
                                    .methodReference!!
                                    .name
                            }
                        }(Z)V
                    """,
                )
            }
        }
    }
