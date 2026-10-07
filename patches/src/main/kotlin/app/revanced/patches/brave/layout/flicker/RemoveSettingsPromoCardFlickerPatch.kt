package app.revanced.patches.brave.layout.flicker

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.brave.premium.unlockOriginPatch
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
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
            val setVisibleMethodName = preferenceSetVisibleMethod.name

            promoCardConstructorMethod.apply {
                val superInitIndex = indexOfFirstInstructionOrThrow(Opcode.INVOKE_DIRECT) + 1
                addInstructions(
                    superInitIndex,
                    """
                        const/4 p1, 0x0
                        invoke-virtual { p0, p1 }, Landroidx/preference/Preference;->$setVisibleMethodName(Z)V
                    """,
                )
            }
        }
    }
