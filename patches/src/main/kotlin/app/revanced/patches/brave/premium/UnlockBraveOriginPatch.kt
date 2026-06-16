package app.revanced.patches.brave.premium


import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.removeInstructions
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Suppress("unused")
val unlockBraveOriginPatch = bytecodePatch(
    name = "Unlock Brave Origin",
    description = "Unlocks the Brave Origin premium menu and bypasses subscription verification spinners."
) {
    compatibleWith("com.brave.browser")

    apply {
        // Spoof Enterprise Policies for VPN, Rewards, News, Wallet, Leo AI, and Web Discovery
        spoofBraveEnterprisePolicies()

        // 1. Force cached credential summary to true
        hasOriginCachedMethod.returnEarlyTrue()

        // 2. Bypass "fetching credentials" infinite spinner
        isFetchingCredentialsMethod.returnEarlyFalse()

        // 3. Spoof PrefService to make C++ engine believe purchase is fully validated
        val factoryInstr = isOriginSubscriptionActiveMethod.implementation?.instructions?.let { insns ->
            insns.filterIsInstance<ReferenceInstruction>().find { instr ->
                instr.opcode.name == "invoke-static" && 
                (instr.reference as? MethodReference)?.returnType == "Lorg/chromium/components/prefs/PrefService;"
            }
        }
        val factoryRef = factoryInstr?.reference as? MethodReference
        
        if (factoryRef != null) {
            val factoryClass = factoryRef.definingClass
            val factoryMethod = factoryRef.name
            val activeImpl = isOriginSubscriptionActiveMethod.implementation ?: return@apply
            val activeCount = activeImpl.instructions.count()
            
            isOriginSubscriptionActiveMethod.removeInstructions(0, activeCount)
            
            val dynamicSmali = """
                if-nez p0, :cond_patched
                const/4 v0, 0x1
                return v0
                
                :cond_patched
                invoke-static {p0}, $factoryClass->$factoryMethod(Lorg/chromium/content_public/browser/BrowserContextHandle;)Lorg/chromium/components/prefs/PrefService;
                move-result-object p0
                
                const-string v1, "brave.origin.subscription_active_android"
                const/4 v0, 0x1
                invoke-virtual {p0, v1, v0}, Lorg/chromium/components/prefs/PrefService;->f(Ljava/lang/String;Z)V
                
                invoke-static {}, ${braveLocalStateGetMethod.definingClass}->${braveLocalStateGetMethod.name}()Lorg/chromium/components/prefs/PrefService;
                move-result-object p0
                
                const-string v1, "brave.origin.purchase_validated"
                invoke-virtual {p0, v1, v0}, Lorg/chromium/components/prefs/PrefService;->f(Ljava/lang/String;Z)V
                
                const/4 v0, 0x1
                return v0
            """.trimIndent()
            
            isOriginSubscriptionActiveMethod.addInstructions(0, dynamicSmali)
        } else {
            isOriginSubscriptionActiveMethod.returnEarlyTrue()
        }

        // 4. Bypass infinite loading spinner in the UI directly
        val method = braveOriginPreferencesMethodMatch
        val impl = method.implementation ?: return@apply
        val instructions = impl.instructions.toList()
        val invokeStaticInstr = instructions.withIndex().find { (_, instr) ->
            instr.opcode.name == "invoke-static" && 
            (instr as? ReferenceInstruction)?.reference?.let { ref ->
                (ref as? MethodReference)?.parameterTypes?.firstOrNull()?.toString() == "Lorg/chromium/chrome/browser/profiles/Profile;" &&
                (ref as? MethodReference)?.returnType == "Z"
            } == true
        }
        
        if (invokeStaticInstr != null) {
            val index = invokeStaticInstr.index
            if (index + 1 < instructions.size) {
                val moveResultInstr = instructions[index + 1] as? OneRegisterInstruction
                if (moveResultInstr != null && moveResultInstr.opcode.name == "move-result") {
                    val moveResultRegister = moveResultInstr.registerA
                    method.replaceInstruction(index, "const/4 v$moveResultRegister, 0x0")
                    method.replaceInstruction(index + 1, "nop")
                }
            }
        }

        // 5. Force requestCredentialSummary to return true so the UI asks C++ for policy values
        val requestSummaryImpl = requestCredentialSummaryMethod.implementation ?: return@apply
        val requestSummaryCount = requestSummaryImpl.instructions.count()
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
        val onPrefChangeImpl = onPrefChangeMethod.implementation ?: return@apply
        val onPrefChangeInstructions = onPrefChangeImpl.instructions.toList()
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
                    invoke-static {}, ${braveLocalStateGetMethod.definingClass}->${braveLocalStateGetMethod.name}()Lorg/chromium/components/prefs/PrefService;
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
