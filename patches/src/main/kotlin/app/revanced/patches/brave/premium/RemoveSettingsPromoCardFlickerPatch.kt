package app.revanced.patches.brave.premium

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.removeInstructions
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Suppress("unused")
val removeSettingsPromoCardFlickerPatch = bytecodePatch(
    name = "Remove Settings Promo Card Flicker",
    description = "Removes the 500ms layout flicker caused by the Settings Promo Card (Sign in to sync banner)."
) {
    compatibleWith("com.brave.browser")

    apply {
        val promoCardClassDef = classDefs.firstOrNull { it.type == "Lorg/chromium/chrome/browser/ui/settings_promo_card/SettingsPromoCardPreference;" }
        
        if (promoCardClassDef != null) {
            val promoCardClass = classDefs.getOrReplaceMutable(promoCardClassDef)
            
            val promoInit = promoCardClass.methods.firstOrNull { it.name == "<init>" }
            val promoBind = promoCardClass.methods.firstOrNull { it.name != "<init>" && it.parameters.toList().size == 1 && it.returnType == "V" }

            // Dynamically find the obfuscated setVisible(boolean) method name from onBindViewHolder
            val setVisibleMethodName = promoBind?.implementation?.instructions?.filterIsInstance<ReferenceInstruction>()?.firstOrNull {
                it.opcode.name == "invoke-virtual" &&
                (it.reference as? MethodReference)?.definingClass == "Landroidx/preference/Preference;" &&
                (it.reference as? MethodReference)?.returnType == "V" &&
                (it.reference as? MethodReference)?.parameterTypes?.firstOrNull()?.toString() == "Z"
            }?.let { (it.reference as MethodReference).name }

            if (promoInit != null && setVisibleMethodName != null) {
                val newInitSmali = """
                    invoke-direct {p0, p1, p2}, Landroidx/preference/Preference;-><init>(Landroid/content/Context;Landroid/util/AttributeSet;)V
                    const/4 p1, 0x0
                    invoke-virtual {p0, p1}, Landroidx/preference/Preference;->$setVisibleMethodName(Z)V
                    return-void
                """.trimIndent()
                
                val initImpl = promoInit.implementation ?: return@apply
                promoInit.removeInstructions(0, initImpl.instructions.count())
                promoInit.implementation = MutableMethodImplementation(3)
                promoInit.addInstructions(0, newInitSmali)
            }
        }
    }
}
