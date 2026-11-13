package app.revanced.patches.viber.layout.ai

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.shared.misc.mapping.ResourceType
import app.revanced.patches.shared.misc.mapping.resourceMappingPatch
import app.revanced.util.cloneMutable
import app.revanced.util.numberOfParameterRegisters
import app.revanced.util.numberOfParameterRegistersLogical
import app.revanced.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import java.util.logging.Logger

/** Preferences that tell if an AI feature is shown: they are redirected to a key that is never set, with `false`. */
private val AI_FEATURE_KEYS = setOf(
    "display_chat_summary_feature_key",
    "display_image_remix_feature_key",
    "display_ai_in_chat_feature_key",
    "ai_reply_is_display_feature_setting_enabled",
    "smart_recap_is_enabled",
)

/** The banner that introduces the AI features: it is redirected to a key that is never set, with `true`. */
private const val AI_BANNER_DISMISSED_KEY = "general_ai_features_banner_dismissed_key"

private const val REDIRECTED_KEY_PREFIX = "revanced_disabled_"

@Suppress("unused")
val disableAiFeaturesPatch = bytecodePatch(
    name = "Disable AI features",
    description = "(PORTED) Removes the AI features: chat summaries, AI reply and AI in chat (Viber 27.x and newer), " +
        "image remix (Viber 28.x and newer), and ChatGPT search, inline translate and the news brief " +
        "(Viber 29.x and newer). Each feature is only patched in the versions that have it.",
) {
    compatibleWith("com.viber.voip")

    dependsOn(resourceMappingPatch)

    apply {
        val logger = Logger.getLogger(this::class.java.name)
        var patched = 0

        // ChatGPT entry point of the search.
        findChatGptEntryAvailability()?.let { method ->
            firstMethod(method).returnEarly(false)
            patched++
        }

        // ChatGPT row of the settings.
        val settingsRowId = runCatching { ResourceType.STRING["pref_category_manage_ai_features_key"] }.getOrNull()
        if (settingsRowId != null) {
            findChatGptSettingsRow(settingsRowId)?.let { row ->
                firstMethod(row.method).addInstructions(row.moveResultIndex + 1, "const/16 v${row.register}, 0x0")
                patched++
            }
        }

        // Inline translate.
        findInlineTranslateVisibility()?.let { translate ->
            firstMethod(translate.method).addInstructions(
                translate.moveResultIndex + 1,
                "const/16 v${translate.register}, 0x0",
            )
            patched++
        }

        // Image remix introduction and presets.
        findImageRemixIntroduction()?.let { method ->
            firstMethod(method).returnEarly()
            patched++
        }
        findImageRemixPresets()?.let { presets ->
            firstMethod(presets.method).addInstructions(presets.moveResultIndex + 1, "const/16 v${presets.register}, 0x0")
            patched++
        }

        // News brief banner: it is never eligible.
        findNewsBriefEligibility()?.let { method ->
            val mutableMethod = firstMethod(method)

            // `this` and the continuation are parameter registers, v0 must be a local register.
            val parameterRegisters = if (AccessFlags.STATIC.isSet(method.accessFlags)) 1 else 2
            if (mutableMethod.implementation!!.registerCount - parameterRegisters < 1) {
                throw PatchException("No free register in the news brief method.")
            }

            mutableMethod.addInstructions(
                0,
                """
                    sget-object v0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
                    return-object v0
                """,
            )
            patched++
        }

        // Preferences.
        val sites = findPreferenceSites(AI_FEATURE_KEYS + AI_BANNER_DISMISSED_KEY)
        if (sites.isEmpty()) logger.info("No AI preferences found.")

        sites.groupBy { it.method }.forEach { (method, methodSites) ->
            var mutableMethod = firstMethod(method)
            var firstIndexOffset = 0
            var addedRegisters: List<Int>? = null

            // Methods without two free registers get two new registers, that start at the old register count.
            if (methodSites.any { it.freeRegisters.size < 2 }) {
                val registerCount = method.implementation!!.registerCount

                if (registerCount + 1 > 15 || method.implementation!!.tryBlocks.isNotEmpty()) {
                    logger.warning("No registers can be added to redirect the preferences of ${method.definingClass}->${method.name}.")
                    return@forEach
                }

                val cloned = method.cloneMutable(additionalRegisters = method.numberOfParameterRegisters + 2)
                mutableMethod.classDef.methods.apply {
                    remove(mutableMethod)
                    add(cloned)
                }

                mutableMethod = cloned
                firstIndexOffset = method.numberOfParameterRegistersLogical
                addedRegisters = listOf(registerCount, registerCount + 1)
            }

            // Insert from the last one to the first one, so the indexes stay valid.
            methodSites.sortedByDescending { it.callIndex }.forEach { site ->
                val registers = addedRegisters ?: site.freeRegisters
                val keyRegister = registers[0]
                val defaultRegister = registers[1]
                val default = if (site.key == AI_BANNER_DISMISSED_KEY) 1 else 0
                val callIndex = site.callIndex + firstIndexOffset

                mutableMethod.addInstructions(
                    callIndex,
                    """
                        const-string v$keyRegister, "$REDIRECTED_KEY_PREFIX${site.key}"
                        const/4 v$defaultRegister, 0x$default
                    """,
                )

                // The original call is now two instructions later. It gets the new key and default.
                val call = site.call
                val invoke = when (site.callOpcode) {
                    Opcode.INVOKE_DIRECT -> "invoke-direct"
                    Opcode.INVOKE_VIRTUAL -> "invoke-virtual"
                    else -> "invoke-interface"
                }
                mutableMethod.replaceInstruction(
                    callIndex + 2,
                    "$invoke { v${site.receiverRegister}, v$keyRegister, v$defaultRegister }, " +
                        "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}",
                )
                patched++
            }
        }

        if (patched == 0) {
            throw PatchException("Could not find any AI feature. This Viber version is not supported.")
        }
    }
}
