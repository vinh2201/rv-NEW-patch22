package app.revanced.patches.brave.premium

import app.revanced.patcher.classDef
import app.revanced.patcher.extensions.*
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.cloneMutable
import app.revanced.util.indexOfFirstInstruction
import app.revanced.util.indexOfFirstInstructionOrThrow
import app.revanced.util.returnEarly
import com.android.tools.smali.dexlib2.Opcode
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
            // Find setBoolean method name dynamically in PrefService
            val prefServiceClass = classDefs.first { it.type == "Lorg/chromium/components/prefs/PrefService;" }
            val setBooleanMethod = prefServiceClass.methods.first { method ->
                method.returnType == "V" &&
                    method.parameterTypes.size == 2 &&
                    method.parameterTypes[0].toString() == "Ljava/lang/String;" &&
                    method.parameterTypes[1].toString() == "Z"
            }
            val setBooleanMethodName = setBooleanMethod.name

            // 1. Force cached credential summary to true.
            getHasOriginCachedMethod().returnEarly(true)

            // 2. Bypass "fetching credentials" infinite spinner.
            getIsFetchingCredentialsMethod().returnEarly(false)

            // 3. Spoof PrefService to make C++ engine believe purchase is fully validated.
            val isOriginSubscriptionActiveMethod = getIsOriginSubscriptionActiveMethod()
            val factoryMethodIndex =
                isOriginSubscriptionActiveMethod.indexOfFirstInstructionOrThrow {
                    opcode == Opcode.INVOKE_STATIC &&
                        methodReference?.returnType == "Lorg/chromium/components/prefs/PrefService;"
                }
            val factoryMethodReference =
                isOriginSubscriptionActiveMethod.getInstruction(factoryMethodIndex).methodReference!!

            val originClassDef = classDefs.getOrReplaceMutable(isOriginSubscriptionActiveMethod.classDef)

            isOriginSubscriptionActiveMethod.cloneMutable(additionalRegisters = 4).apply {
                addInstructions(
                    0,
                    """
                        if-nez p0, :cond_patched
                        const/4 v0, 0x1
                        return v0
                        
                        :cond_patched
                        invoke-static {p0}, $factoryMethodReference
                        move-result-object v2
                        
                        const-string v1, "brave.origin.subscription_active_android"
                        const/4 v0, 0x1
                        invoke-virtual { v2, v1, v0 }, Lorg/chromium/components/prefs/PrefService;->$setBooleanMethodName(Ljava/lang/String;Z)V
                        
                        invoke-static {}, ${getBraveLocalStateGetMethod()}
                        move-result-object v2
                        
                        const-string v1, "brave.origin.purchase_validated"
                        invoke-virtual { v2, v1, v0 }, Lorg/chromium/components/prefs/PrefService;->$setBooleanMethodName(Ljava/lang/String;Z)V
                        
                        const/4 v0, 0x1
                        return v0
                    """,
                )
            }.let {
                originClassDef.methods -= isOriginSubscriptionActiveMethod
                originClassDef.methods += it
            }

            // 4. Bypass infinite loading spinner in the UI directly.
            onCreatePreferencesMethod.apply {
                val moveResultRegister = method.getInstruction<OneRegisterInstruction>(this[5]).registerA

                method.replaceInstruction(this[5], "const/4 v$moveResultRegister, 0x0")
            }

            // 5. Force requestCredentialSummary to return true so the UI asks C++ for policy values.
            getRequestCredentialSummaryMethod().addInstructions(
                0,
                """
                    if-eqz p1, :cond_end
                    const/4 v0, 0x1
                    invoke-static { v0 }, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
                    move-result-object v0
                    invoke-interface { p1, v0 }, Lorg/chromium/base/Callback;->onResult(Ljava/lang/Object;)V
                    :cond_end
                    return-void
                """,
            )

            // 6. Intercept BraveOriginPreferences.onPreferenceChange to spoof purchase state before Mojo IPC.
            getBraveOriginPreferencesOnPreferenceChangeMethod().apply {
                val invokeDirectIndex =
                    indexOfFirstInstruction {
                        opcode == Opcode.INVOKE_DIRECT &&
                            methodReference?.definingClass?.startsWith(
                                "Lorg/chromium/chrome/browser/settings/BraveOriginPreferences$$",
                            ) == true
                    }

                if (invokeDirectIndex != -1) {
                    val invokeInterfaceIndex =
                        indexOfFirstInstruction(invokeDirectIndex + 1) {
                            opcode == Opcode.INVOKE_INTERFACE || opcode == Opcode.INVOKE_INTERFACE_RANGE
                        }

                    if (invokeInterfaceIndex != -1) {
                        val invokeInterfaceInstruction =
                            getInstruction(invokeInterfaceIndex)
                        val (registerC, registerD, registerE) =
                            when (invokeInterfaceInstruction) {
                                is FiveRegisterInstruction -> {
                                    Triple(
                                        invokeInterfaceInstruction.registerC,
                                        invokeInterfaceInstruction.registerD,
                                        invokeInterfaceInstruction.registerE,
                                    )
                                }

                                is RegisterRangeInstruction -> {
                                    Triple(
                                        invokeInterfaceInstruction.startRegister,
                                        invokeInterfaceInstruction.startRegister + 1,
                                        invokeInterfaceInstruction.startRegister + 2,
                                    )
                                }

                                else -> error("Unknown instruction format for invoke-interface")
                            }

                        addInstructions(
                            invokeInterfaceIndex + 1,
                            """
                                invoke-static {}, ${getBraveLocalStateGetMethod()}
                                move-result-object v$registerC
                                const-string v$registerD, "brave.origin.purchase_validated"
                                const/4 v$registerE, 1
                                invoke-virtual { v$registerC, v$registerD, v$registerE }, Lorg/chromium/components/prefs/PrefService;->$setBooleanMethodName(Ljava/lang/String;Z)V
                            """,
                        )
                    }
                }
            }

            // 7. Disable showOriginSettingsForRestart to prevent settings page from auto-opening on startup.
            getShowOriginSettingsForRestartMethod().returnEarly()
        }
    }
