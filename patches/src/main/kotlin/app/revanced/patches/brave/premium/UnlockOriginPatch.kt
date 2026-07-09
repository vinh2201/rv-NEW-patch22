package app.revanced.patches.brave.premium

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.classDef
import app.revanced.patcher.extensions.*
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.cloneMutable
import app.revanced.util.indexOfFirstInstruction
import app.revanced.util.indexOfFirstInstructionOrThrow
import app.revanced.util.returnEarly
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

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

            // 2. Bypass "fetching credentials" infinite spinner.
            isFetchingCredentialsMethod.returnEarly(false)

            // 3. Spoof PrefService to make C++ engine believe purchase is fully validated.
            val factoryMethodIndex =
                isOriginSubscriptionActiveMethod.indexOfFirstInstructionOrThrow {
                    opcode == Opcode.INVOKE_STATIC &&
                        methodReference?.returnType == "Lorg/chromium/components/prefs/PrefService;"
                }
            val factoryMethodReference =
                isOriginSubscriptionActiveMethod.getInstruction(factoryMethodIndex).methodReference!!

            val clonedIsOriginSubscriptionActiveMethod = isOriginSubscriptionActiveMethod.cloneMutable(additionalRegisters = 4)
            val originClass = classDefs.getOrReplaceMutable(isOriginSubscriptionActiveMethod.classDef)
            originClass.methods.apply {
                remove(isOriginSubscriptionActiveMethod)
                add(clonedIsOriginSubscriptionActiveMethod)
            }

            clonedIsOriginSubscriptionActiveMethod.addInstructions(
                0,
                """
                    if-nez p0, :cond_patched
                    const/4 v0, 0x1
                    return v0
                    
                    :cond_patched
                    invoke-static {p0}, ${factoryMethodReference.definingClass}->${factoryMethodReference.name}(Lorg/chromium/content_public/browser/BrowserContextHandle;)Lorg/chromium/components/prefs/PrefService;
                    move-result-object v2
                    
                    const-string v1, "brave.origin.subscription_active_android"
                    const/4 v0, 0x1
                    invoke-virtual {v2, v1, v0}, Lorg/chromium/components/prefs/PrefService;->f(Ljava/lang/String;Z)V
                    
                    invoke-static {}, ${braveLocalStateGetMethod.definingClass}->${braveLocalStateGetMethod.name}()Lorg/chromium/components/prefs/PrefService;
                    move-result-object v2
                    
                    const-string v1, "brave.origin.purchase_validated"
                    invoke-virtual {v2, v1, v0}, Lorg/chromium/components/prefs/PrefService;->f(Ljava/lang/String;Z)V
                    
                    const/4 v0, 0x1
                    return v0
                """,
            )

            // 4. Bypass infinite loading spinner in the UI directly.
            onCreatePreferencesMethod.apply {
                val moveResultRegister = method.getInstruction<OneRegisterInstruction>(this[5]).registerA

                method.replaceInstruction(this[5], "const/4 v$moveResultRegister, 0x0")
            }

            // 5. Force requestCredentialSummary to return true so the UI asks C++ for policy values.
            requestCredentialSummaryMethod.addInstructions(
                0,
                """
                    if-eqz p1, :cond_end
                    const/4 v0, 0x1
                    invoke-static { v0 }, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
                    move-result-object v0
                    invoke-interface {p1, v0}, Lorg/chromium/base/Callback;->onResult(Ljava/lang/Object;)V
                    :cond_end
                    return-void
                """,
            )

            // 6. Intercept BraveOriginPreferences.onPreferenceChange to spoof purchase state before Mojo IPC.
            braveOriginPreferencesOnPreferenceChangeMethod.apply {
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
                            getInstruction<Instruction>(invokeInterfaceIndex)
                        val (vA, vB, vC) =
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

                                else -> {
                                    error("Unknown instruction format for invoke-interface")
                                }
                            }

                        addInstructions(
                            invokeInterfaceIndex + 1,
                            """
                                invoke-static { }, ${"$"}{braveLocalStateGetMethod.definingClass}->${"$"}{braveLocalStateGetMethod.name}()Lorg/chromium/components/prefs/PrefService;
                                move-result-object v$vA
                                const-string v$vB, "brave.origin.purchase_validated"
                                const/4 v$vC, 0x1
                                invoke-virtual {v$vA, v$vB, v$vC}, Lorg/chromium/components/prefs/PrefService;->f(Ljava/lang/String;Z)V
                            """,
                        )
                    }
                }
            }

            // 7. Disable showOriginSettingsForRestart to prevent settings page from auto-opening on startup.
            showOriginSettingsForRestartMethod.returnEarly()
        }
    }
