package app.revanced.patches.brave.premium

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

@Suppress("unused")
val spoofBraveEnterprisePoliciesPatch =
    bytecodePatch(
        name = "Spoof Brave enterprise policies",
        description = "Spoofs enterprise policies to configure debloated features in Brave.",
    ) {
        compatibleWith("com.brave.browser")
        dependsOn(unlockOriginPatch)

        extendWith("extensions/brave.rve")

        apply {
            // Hook AppRestrictionsProvider to spoof enterprise policies for Brave Origin toggles
            val appRestrictionsMethod = appRestrictionsMethod

            val className = appRestrictionsMethod.definingClass
            val classDef = classDefs.first { it.type == className }
            val superClassName = classDef.superclass
            val contextField = classDef.contextFieldName

            val bMethodMatch =
                classDef.getAppRestrictionsProviderOnRestrictionsReceivedMethodMatch(
                    appRestrictionsMethod,
                )
            val bMethod =
                classDefs.getOrReplaceMutable(classDef).methods.firstMethod(bMethodMatch.method)

            val aMethodName =
                bMethodMatch.let { match ->
                    val aIndex = match[0]
                    match.method
                        .getInstruction<ReferenceInstruction>(aIndex)
                        .methodReference!!
                        .name
                }

            val spoofSmali =
                """
                iget-object v0, p0, $className->$contextField:Landroid/content/Context;
                invoke-static {v0}, Lapp/revanced/extension/brave/premium/Policies;->getSpoofedRestrictions(Landroid/content/Context;)Landroid/os/Bundle;
                move-result-object v0
                invoke-virtual {p0, v0}, $superClassName->$aMethodName(Landroid/os/Bundle;)V
                return-void
                """.trimIndent()

            bMethod.implementation = MutableMethodImplementation(2)
            bMethod.addInstructions(0, spoofSmali)
        }
    }
