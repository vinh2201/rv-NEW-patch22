package app.revanced.patches.brave.premium

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.removeInstructions
import app.revanced.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal fun BytecodePatchContext.spoofBraveEnterprisePolicies() {
    val appRestrictionsMethod = appRestrictionsProviderMethodMatch
    
    val className = appRestrictionsMethod.definingClass
    val classDef = classDefs.firstOrNull { it.type == className } ?: return
    val superClassName = classDef.superclass
    
    val bMethod = classDef.methods.firstOrNull { it.name != "<init>" && it.name != appRestrictionsMethod.name && it.returnType == "V" } as? MutableMethod ?: return
    val contextField = classDef.fields.firstOrNull { it.type == "Landroid/content/Context;" }?.name ?: return
    val userManagerField = classDef.fields.firstOrNull { it.type == "Landroid/os/UserManager;" }?.name ?: return
    
    val instructions = bMethod.implementation?.instructions?.toList() ?: return
    val originalA = instructions.firstOrNull { 
        it.opcode.name == "invoke-virtual" && 
        (it as? ReferenceInstruction)?.reference?.toString()?.contains("Landroid/os/Bundle;") == true 
    } as? ReferenceInstruction ?: return
    
    val aMethodName = (originalA.reference as? MethodReference)?.name ?: return
    
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
        """.trimIndent()
    }
    
    val paramWords = bMethod.parameters.sumOf { if (it.type == "J" || it.type == "D") 2 else 1.toInt() }
    val totalParamWords = 1 + paramWords // 1 for 'this'
    val localRegs = 5 // We need v0, v1, v2, v3, v4 for locals
    bMethod.implementation = MutableMethodImplementation(localRegs + totalParamWords)
    val p0 = "v$localRegs"
    
    val spoofSmali = """
        invoke-static {}, Landroid/os/StrictMode;->allowThreadDiskReads()Landroid/os/StrictMode${"$"}ThreadPolicy;
        move-result-object v0
        
        iget-object v1, $p0, $className->$contextField:Landroid/content/Context;
        invoke-virtual {v1}, Landroid/content/Context;->getPackageName()Ljava/lang/String;
        move-result-object v1
        
        iget-object v2, $p0, $className->$userManagerField:Landroid/os/UserManager;
        invoke-static {v2, v1}, $className->${appRestrictionsMethod.name}(Landroid/os/UserManager;Ljava/lang/String;)Landroid/os/Bundle;
        move-result-object v1
        
        invoke-static {v0}, Landroid/os/StrictMode;->setThreadPolicy(Landroid/os/StrictMode${"$"}ThreadPolicy;)V
        
        new-instance v1, Landroid/os/Bundle;
        invoke-direct {v1}, Landroid/os/Bundle;-><init>()V
        
        iget-object v2, $p0, $className->$contextField:Landroid/content/Context;
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
        
        invoke-virtual {$p0, v1}, $superClassName->$aMethodName(Landroid/os/Bundle;)V
        return-void
    """.trimIndent()
    
    bMethod.addInstructions(0, spoofSmali)
}
