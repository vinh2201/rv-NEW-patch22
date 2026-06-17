package app.revanced.patches.brave.premium

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.removeInstructions
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

@Suppress("unused")
val unlockOriginPatch = bytecodePatch(
    name = "Unlock Brave Origin",
    description = "Unlocks Brave Origin to debloat features such as VPN, Rewards, Wallet, Leo, Web Discovery, Analytics, and Statistics."
) {
    compatibleWith("com.brave.browser")

    apply {
        // 1. Force cached credential summary to true
        val cachedCount = hasOriginCachedMethod.implementation!!.instructions.count()
        hasOriginCachedMethod.removeInstructions(0, cachedCount)
        hasOriginCachedMethod.addInstructions(0, "const/4 v0, 0x1\nreturn v0")

        // 2. Bypass "fetching credentials" infinite spinner
        val fetchingCount = isFetchingCredentialsMethod.implementation!!.instructions.count()
        isFetchingCredentialsMethod.removeInstructions(0, fetchingCount)
        isFetchingCredentialsMethod.addInstructions(0, "const/4 v0, 0x0\nreturn v0")

        // 3. Spoof PrefService to make C++ engine believe purchase is fully validated
        val factoryInstructions = isOriginSubscriptionActiveMethod.implementation?.instructions?.let { insns ->
            insns.filterIsInstance<ReferenceInstruction>().find { instr ->
                instr.opcode.name == "invoke-static" && 
                (instr.reference as? MethodReference)?.returnType == "Lorg/chromium/components/prefs/PrefService;"
            }
        }
        val factoryRef = factoryInstr?.reference as? MethodReference
        
        if (factoryRef != null) {
            val factoryClass = factoryRef.definingClass
            val factoryMethod = factoryRef.name
            val activeCount = isOriginSubscriptionActiveMethod.implementation!!.instructions.count()
            
            isOriginSubscriptionActiveMethod.removeInstructions(0, activeCount)
            
            val dynamicSmali = """
                if-nez p0, :cond_patched
                const/4 v0, 0x1
                return v0
                
                :cond_patched
                invoke-static {p0}, ${"$"}{factoryClass}->${"$"}{factoryMethod}(Lorg/chromium/content_public/browser/BrowserContextHandle;)Lorg/chromium/components/prefs/PrefService;
                move-result-object p0
                
                const-string v1, "brave.origin.subscription_active_android"
                const/4 v0, 0x1
                invoke-virtual {p0, v1, v0}, Lorg/chromium/components/prefs/PrefService;->f(Ljava/lang/String;Z)V
                
                invoke-static {}, ${"$"}{braveLocalStateGetMethod.definingClass}->${"$"}{braveLocalStateGetMethod.name}()Lorg/chromium/components/prefs/PrefService;
                move-result-object p0
                
                const-string v1, "brave.origin.purchase_validated"
                invoke-virtual {p0, v1, v0}, Lorg/chromium/components/prefs/PrefService;->f(Ljava/lang/String;Z)V
                
                const/4 v0, 0x1
                return v0
            """.trimIndent()
            
            isOriginSubscriptionActiveMethod.addInstructions(0, dynamicSmali)
        } else {
            val activeCount = isOriginSubscriptionActiveMethod.implementation!!.instructions.count()
            isOriginSubscriptionActiveMethod.removeInstructions(0, activeCount)
            isOriginSubscriptionActiveMethod.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
        }

        // 4. Bypass infinite loading spinner in the UI directly
        braveOriginPreferencesMethodMatch.let {
            val invokeStaticIndex = it[4]
            val moveResultIndex = it[5]
            val moveResultRegister = it.method.getInstruction<OneRegisterInstruction>(moveResultIndex).registerA

            it.method.replaceInstruction(invokeStaticIndex, "const/4 v$moveResultRegister, 0x0")
            it.method.replaceInstruction(moveResultIndex, "nop")
        }

        // 5. Force requestCredentialSummary to return true so the UI asks C++ for policy values
        val requestSummaryCount = requestCredentialSummaryMethod.implementation!!.instructions.count()
        requestCredentialSummaryMethod.removeInstructions(0, requestSummaryCount)
        
        val smali = """
            if-eqz p1, :cond_end
            const/4 v0, 0x1
            invoke-static {v0}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
            move-result-object v0
            invoke-interface {p1, v0}, Lorg/chromium/base/Callback;->onResult(Ljava/lang/Object;)V
            :cond_end
            return-void
        """.trimIndent()
        
        requestCredentialSummaryMethod.addInstructions(0, smali)
        
        // 6. Intercept BraveOriginPreferences.onPreferenceChange to spoof purchase state before Mojo IPC
        @Suppress("DEPRECATION")
        val braveOriginPreferencesFingerprint = app.revanced.patcher.fingerprint {
            custom { method, classDef ->
                val params = method.parameters.toList()
                classDef.type == "Lorg/chromium/chrome/browser/settings/BraveOriginPreferences;" && 
                params.size == 2 && 
                params[0].type == "Landroidx/preference/Preference;" && 
                params[1].type == "Ljava/lang/Object;" && 
                method.returnType == "Z"
            }
        }
        val onPrefChangeMethod = braveOriginPreferencesFingerprint.method
        
        val onPrefChangeInstructions = onPrefChangeMethod.implementation!!.instructions.toList()
        val invokeDirectIndex = onPrefChangeInstructions.indexOfFirst {
            it.opcode.name == "invoke-direct" && 
            (it as? ReferenceInstruction)?.reference?.let { ref ->
                (ref as? MethodReference)?.definingClass?.startsWith("Lorg/chromium/chrome/browser/settings/BraveOriginPreferences$$") == true
            } == true
        }
        
        if (invokeDirectIndex != -1) {
            var invokeInterfaceIndex = -1
            var invokeInterfaceInstr: com.android.tools.smali.dexlib2.iface.instruction.Instruction? = null
            for (i in (invokeDirectIndex + 1) until onPrefChangeInstructions.size) {
                val instr = onPrefChangeInstructions[i]
                if (instr.opcode.name.startsWith("invoke-interface")) {
                    invokeInterfaceIndex = i
                    invokeInterfaceInstr = instr
                    break
                }
            }
            
            if (invokeInterfaceIndex != -1 && invokeInterfaceInstr != null) {
                val (vA, vB, vC) = when (invokeInterfaceInstr) {
                    is FiveRegisterInstruction -> Triple(invokeInterfaceInstr.registerC, invokeInterfaceInstr.registerD, invokeInterfaceInstr.registerE)
                    is RegisterRangeInstruction -> Triple(invokeInterfaceInstr.startRegister, invokeInterfaceInstr.startRegister + 1, invokeInterfaceInstr.startRegister + 2)
                    else -> error("Unknown instruction format for invoke-interface")
                }
                
                val spoofSmali = """
                    invoke-static {}, ${"$"}{braveLocalStateGetMethod.definingClass}->${"$"}{braveLocalStateGetMethod.name}()Lorg/chromium/components/prefs/PrefService;
                    move-result-object v$vA
                    const-string v$vB, "brave.origin.purchase_validated"
                    const/4 v$vC, 0x1
                    invoke-virtual {v$vA, v$vB, v$vC}, Lorg/chromium/components/prefs/PrefService;->f(Ljava/lang/String;Z)V
                """.trimIndent()
                
                onPrefChangeMethod.addInstructions(invokeInterfaceIndex + 1, spoofSmali)
            }
        }
    }
}
