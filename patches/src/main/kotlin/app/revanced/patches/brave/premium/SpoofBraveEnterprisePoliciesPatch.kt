package app.revanced.patches.brave.premium

import app.revanced.patcher.classDef
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.brave.misc.extension.sharedExtensionPatch

@Suppress("unused")
val spoofBraveEnterprisePoliciesPatch =
    bytecodePatch(
        name = "Spoof Brave enterprise policies",
        description = "Spoofs enterprise policies to configure debloated features in Brave.",
    ) {
        compatibleWith("com.brave.browser")
        dependsOn(unlockOriginPatch, sharedExtensionPatch)

        apply {
            // Hook AppRestrictionsProvider to spoof enterprise policies for Brave Origin toggles.
            val appRestrictionsMethod = getAppRestrictionsMethod()

            val classDef = classDefs.getOrReplaceMutable(appRestrictionsMethod.classDef)
            val superClassDescriptor = classDef.superclass
            val contextField = classDef.getContextField()

            val onRestrictionsReceivedMethodMatch =
                classDef.getAppRestrictionsProviderOnRestrictionsReceivedMethodMatch(appRestrictionsMethod)
            val onRestrictionsReceivedMethod =
                classDef.firstMethod(onRestrictionsReceivedMethodMatch.method)

            val onRestrictionsReceivedCallbackMethodName =
                onRestrictionsReceivedMethodMatch.let { match ->
                    val callbackInstructionIndex = match[0]
                    match.method
                        .getInstruction(callbackInstructionIndex)
                        .methodReference!!
                        .name
                }

            onRestrictionsReceivedMethod.addInstructions(
                0,
                """
                    iget-object v0, p0, $contextField
                    invoke-static {v0}, Lapp/revanced/extension/brave/premium/SpoofBraveEnterprisePoliciesPatch;->getSpoofedRestrictions(Landroid/content/Context;)Landroid/os/Bundle;
                    move-result-object v0
                    invoke-virtual { p0, v0 }, $superClassDescriptor->$onRestrictionsReceivedCallbackMethodName(Landroid/os/Bundle;)V
                    return-void
                """,
            )
        }
    }
