package app.revanced.patches.brave.premium

import app.revanced.patcher.extensions.*
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.returnEarly
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction

@Suppress("unused")
val unlockOriginPatch =
    bytecodePatch(
        name = "Unlock Brave Origin",
        description = "Unlocks Brave Origin to debloat features such as VPN, Rewards, Wallet, Leo, Web Discovery, Analytics, and Statistics.",
    ) {
        compatibleWith("com.brave.browser")

        apply {
            // 1. Force cached credential summary to true.
            hasOriginCachedMethod.returnEarly(true)

            // 2. Force origin subscription active check to return true.
            isOriginSubscriptionActiveMethod.returnEarly(true)

            // 3. Bypass infinite loading spinner in the UI directly.
            onCreatePreferencesMethodMatch.let {  
                it.method.apply { 
                    val moveResultIndex = it[5]
                    val moveResultRegister = getInstruction<OneRegisterInstruction>(moveResultIndex).registerA

                    replaceInstruction(moveResultIndex, "const/4 v$moveResultRegister, 0x0")
                }
            }

            // 4. Force requestCredentialSummary to return true so the UI asks C++ for policy values.
            requestCredentialSummaryMethod.addInstructions(
                0,
                """
                    if-eqz p1, :cond_end
                    sget-object v0, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
                    invoke-interface { p1, v0 }, Lorg/chromium/base/Callback;->onResult(Ljava/lang/Object;)V
                    :cond_end
                    return-void
                """,
            )

            // 5. Intercept BraveOriginPreferences.onPreferenceChange to spoof purchase state before Mojo IPC.
            braveOriginPreferencesOnPreferenceChangeMethodMatch.apply {
                val invokeInstructionIndex = this[0]
                val invokeInstruction = method.getInstruction(invokeInstructionIndex)
                val (registerC, registerD, registerE) = when (invokeInstruction) {
                    is FiveRegisterInstruction -> Triple(
                        invokeInstruction.registerC,
                        invokeInstruction.registerD,
                        invokeInstruction.registerE,
                    )
                    is RegisterRangeInstruction -> Triple(
                        invokeInstruction.startRegister,
                        invokeInstruction.startRegister + 1,
                        invokeInstruction.startRegister + 2,
                    )
                    else -> error("Unknown instruction format for invoke instruction: $invokeInstruction")
                }

                val setBooleanMethodName = prefServiceSetBooleanMethod.name

                method.addInstructions(
                    invokeInstructionIndex + 1,
                    """
                        invoke-static {}, $braveLocalStateGetMethod
                        move-result-object v$registerC
                        const-string v$registerD, "brave.origin.purchase_validated"
                        const/4 v$registerE, 1
                        invoke-virtual { v$registerC, v$registerD, v$registerE }, Lorg/chromium/components/prefs/PrefService;->$setBooleanMethodName(Ljava/lang/String;Z)V
                    """,
                )
            }

            // 6. Disable showOriginSettingsForRestart to prevent settings page from auto-opening on startup.
            showOriginSettingsForRestartMethod.returnEarly()
        }
    }
