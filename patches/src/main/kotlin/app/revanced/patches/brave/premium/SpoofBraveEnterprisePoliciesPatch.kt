package app.revanced.patches.brave.premium

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.removeInstructions
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

@Suppress("unused")
val spoofBraveEnterprisePoliciesPatch = bytecodePatch(
    name = "Spoof Brave enterprise policies",
    description = "Spoofs enterprise policies to configure debloated features in Brave."
) {
    compatibleWith("com.brave.browser")
    dependsOn(unlockOriginPatch)

    apply {
        // Hook AppRestrictionsProvider to spoof enterprise policies for Brave Origin toggles
        @Suppress("DEPRECATION")
        val appRestrictionsProviderFingerprint = app.revanced.patcher.fingerprint {
            custom { method, _ ->
                val params = method.parameters.toList()
                if (params.size == 2 && params[0].type == "Landroid/os/UserManager;" && params[1].type == "Ljava/lang/String;" && method.returnType == "Landroid/os/Bundle;") {
                    val impl = method.implementation ?: return@custom false
                    impl.instructions.any { instr ->
                        instr is ReferenceInstruction &&
                        (instr.reference as? StringReference)?.string == "cr_AppResProvider"
                    }
                } else {
                    false
                }
            }
        }
        
        val appRestrictionsMethod = appRestrictionsProviderFingerprint.method
        
        val className = appRestrictionsMethod.definingClass
        val classDef = classDefs.first { it.type == className }
        val superClassName = classDef.superclass
        val bMethod = classDef.methods.first { it.name != "<init>" && it.name != appRestrictionsMethod.name && it.returnType == "V" } as MutableMethod
        val contextField = classDef.fields.first { it.type == "Landroid/content/Context;" }.name
        val userManagerField = classDef.fields.first { it.type == "Landroid/os/UserManager;" }.name
        
        val instructions = bMethod.implementation!!.instructions.toList()
        val originalA = instructions.firstOrNull { 
            it.opcode.name == "invoke-virtual" && 
            (it as? ReferenceInstruction)?.reference?.toString()?.contains("Landroid/os/Bundle;") == true 
        }
        val aMethodName = ((originalA as ReferenceInstruction).reference as MethodReference).name
        
        bMethod.removeInstructions(0, instructions.size)
        
        val policies = listOf(
            "news_switch" to "BraveNewsDisabled",
            "rewards_switch" to "BraveRewardsDisabled",
            "vpn_switch" to "BraveVPNDisabled",
            "wallet_switch" to "BraveWalletDisabled"
        )
        
        val policyInjections = policies.joinToString("\n") { (prefKey, policyName) ->
            """
            const-string v0, "$prefKey"
            invoke-interface {v2, v0, v3}, Landroid/content/SharedPreferences;->getBoolean(Ljava/lang/String;Z)Z
            move-result v0
            if-nez v0, :cond_skip_$prefKey
            const/4 v0, 0x1
            const-string v4, "$policyName"
            invoke-virtual {v1, v4, v0}, Landroid/os/Bundle;->putBoolean(Ljava/lang/String;Z)V
                :cond_skip_$prefKey
            """
        }
        
        val spoofSmali = """
            invoke-static {}, Landroid/os/StrictMode;->allowThreadDiskReads()Landroid/os/StrictMode${"$"}ThreadPolicy;
            move-result-object v0
            
            iget-object v1, v5, $className->$contextField:Landroid/content/Context;
            invoke-virtual {v1}, Landroid/content/Context;->getPackageName()Ljava/lang/String;
            move-result-object v1
            
            iget-object v2, v5, $className->$userManagerField:Landroid/os/UserManager;
            invoke-static {v2, v1}, $className->${appRestrictionsMethod.name}(Landroid/os/UserManager;Ljava/lang/String;)Landroid/os/Bundle;
            move-result-object v1
            
            invoke-static {v0}, Landroid/os/StrictMode;->setThreadPolicy(Landroid/os/StrictMode${"$"}ThreadPolicy;)V
            
            new-instance v1, Landroid/os/Bundle;
            invoke-direct {v1}, Landroid/os/Bundle;-><init>()V
            
            iget-object v2, v5, $className->$contextField:Landroid/content/Context;
            invoke-static {v2}, Landroid/preference/PreferenceManager;->getDefaultSharedPreferences(Landroid/content/Context;)Landroid/content/SharedPreferences;
            move-result-object v2
            
            const/4 v3, 0x0
            
$policyInjections
            
            const-string v0, "leo_ai_switch"
            invoke-interface {v2, v0, v3}, Landroid/content/SharedPreferences;->getBoolean(Ljava/lang/String;Z)Z
            move-result v0
            if-nez v0, :cond_skip_leo
            const-string v4, "BraveAIChatEnabled"
            invoke-virtual {v1, v4, v3}, Landroid/os/Bundle;->putBoolean(Ljava/lang/String;Z)V
            :cond_skip_leo
            
            const-string v0, "web_discovery_project_switch"
            invoke-interface {v2, v0, v3}, Landroid/content/SharedPreferences;->getBoolean(Ljava/lang/String;Z)Z
            move-result v0
            if-nez v0, :cond_skip_wdp
            const-string v4, "BraveWebDiscoveryEnabled"
            invoke-virtual {v1, v4, v3}, Landroid/os/Bundle;->putBoolean(Ljava/lang/String;Z)V
            :cond_skip_wdp
            
            const-string v0, "privacy_preserving_analytics_switch"
            invoke-interface {v2, v0, v3}, Landroid/content/SharedPreferences;->getBoolean(Ljava/lang/String;Z)Z
            move-result v0
            if-nez v0, :cond_skip_p3a
            const-string v4, "BraveP3AEnabled"
            invoke-virtual {v1, v4, v3}, Landroid/os/Bundle;->putBoolean(Ljava/lang/String;Z)V
            :cond_skip_p3a
            
            const-string v0, "statistics_reporting_switch"
            invoke-interface {v2, v0, v3}, Landroid/content/SharedPreferences;->getBoolean(Ljava/lang/String;Z)Z
            move-result v0
            if-nez v0, :cond_skip_stats
            const-string v4, "MetricsReportingEnabled"
            invoke-virtual {v1, v4, v3}, Landroid/os/Bundle;->putBoolean(Ljava/lang/String;Z)V
            :cond_skip_stats
            
            invoke-virtual {v5, v1}, $superClassName->$aMethodName(Landroid/os/Bundle;)V
            return-void
        """.trimIndent()
        
        bMethod.implementation = MutableMethodImplementation(6)
        bMethod.addInstructions(0, spoofSmali)
    }
}
