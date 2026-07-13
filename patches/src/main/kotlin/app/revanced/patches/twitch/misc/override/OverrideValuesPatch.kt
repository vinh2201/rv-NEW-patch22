package app.revanced.patches.twitch.misc.override

import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.all.misc.resources.addResources
import app.revanced.patches.all.misc.resources.addResourcesPatch
import app.revanced.patches.shared.misc.settings.preference.ListPreference
import app.revanced.patches.twitch.misc.settings.PreferenceScreen
import app.revanced.patches.twitch.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.iface.ClassDef

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/revanced/extension/twitch/patches/OverrideValuesPatch;"

// Channel
private const val AFFILIATE = "revanced_override_channel_affiliate"
private const val MONETIZED = "revanced_override_channel_monetized"
private const val PARTNER = "revanced_override_channel_partner"
private const val PARTICIPATING_DJ = "revanced_override_channel_participating_dj"
private const val HAS_TURBO = "revanced_override_channel_has_turbo"
private const val EMAIL_VERIFIED = "revanced_override_channel_email_verified"
private const val CLIPS_CREATION = "revanced_override_channel_clips_creation"

// Content
private const val MATURE = "revanced_override_content_mature"
private const val GAMBLING = "revanced_override_content_gambling"
private const val RESTRICTED = "revanced_override_content_restricted"

// Moderation
private const val BROADCASTER = "revanced_override_chat_broadcaster"
private const val MOD = "revanced_override_chat_mod"
private const val VIP = "revanced_override_chat_vip"
private const val SUBSCRIBED = "revanced_override_chat_subscribed"
private const val VERIFIED = "revanced_override_chat_verified"
private const val FOLLOWING = "revanced_override_chat_following"
private const val BYPASS_RESTRICTIONS = "revanced_override_chat_bypass_restrictions"
private const val FOLLOW_REQUIRED = "revanced_override_chat_follow_required"
private const val SUBSCRIPTION_REQUIRED = "revanced_override_chat_subscription_required"
private const val VERIFICATION_REQUIRED = "revanced_override_chat_verification_required"
private const val EMOTE_ONLY = "revanced_override_chat_emote_only"

// Rewards
private const val REWARD_ENABLED = "revanced_override_reward_enabled"
private const val REWARD_IN_STOCK = "revanced_override_reward_in_stock"
private const val REWARD_PAUSED = "revanced_override_reward_paused"
private const val REWARD_SUB_ONLY = "revanced_override_reward_sub_only"

private fun overridePreference(key: String) = ListPreference(
    key = key,
    entriesKey = "revanced_override_entries",
    entryValuesKey = "revanced_override_entry_values",
)

@Suppress("unused")
val overrideValuesPatch = bytecodePatch(
    name = "Override values",
    description = "Adds various options to override flags.",
) {
    dependsOn(
        settingsPatch,
        addResourcesPatch,
    )

    compatibleWith("tv.twitch.android.app")

    apply {
        addResources("twitch", "misc.override.overrideValuesPatch")

        PreferenceScreen.OVERRIDE.CHANNEL.addPreferences(
            overridePreference(AFFILIATE),
            overridePreference(MONETIZED),
            overridePreference(PARTNER),
            overridePreference(PARTICIPATING_DJ),
            overridePreference(HAS_TURBO),
            overridePreference(EMAIL_VERIFIED),
            overridePreference(CLIPS_CREATION),
        )

        PreferenceScreen.OVERRIDE.CONTENT.addPreferences(
            overridePreference(MATURE),
            overridePreference(GAMBLING),
            overridePreference(RESTRICTED),
        )

        PreferenceScreen.OVERRIDE.MODERATION.addPreferences(
            overridePreference(BROADCASTER),
            overridePreference(MOD),
            overridePreference(VIP),
            overridePreference(SUBSCRIBED),
            overridePreference(VERIFIED),
            overridePreference(FOLLOWING),
            overridePreference(BYPASS_RESTRICTIONS),
            overridePreference(FOLLOW_REQUIRED),
            overridePreference(SUBSCRIPTION_REQUIRED),
            overridePreference(VERIFICATION_REQUIRED),
            overridePreference(EMOTE_ONLY),
        )

        PreferenceScreen.OVERRIDE.REWARDS.addPreferences(
            overridePreference(REWARD_ENABLED),
            overridePreference(REWARD_IN_STOCK),
            overridePreference(REWARD_PAUSED),
            overridePreference(REWARD_SUB_ONLY),
        )

        fun ClassDef.overrideBooleans(vararg methodToSetting: Pair<String, String>) {
            val mutableClass = classDefs.getOrReplaceMutable(this)
            methodToSetting.forEach { (methodName, settingKey) ->
                mutableClass.methods
                    .filter {
                        it.name == methodName && it.returnType == "Z" &&
                            it.parameterTypes.isEmpty() && it.implementation != null
                    }
                    .forEach { method ->
                        method.addInstructionsWithLabels(
                            0,
                            """
                                const-string v0, "$settingKey"
                                invoke-static { v0 }, $EXTENSION_CLASS_DESCRIPTOR->getOverride(Ljava/lang/String;)I
                                move-result v0
                                if-ltz v0, :no_override
                                return v0
                            """,
                            ExternalLabel("no_override", method.getInstruction(0)),
                        )
                    }
            }
        }

        // Channel
        channelInfoClassDef.overrideBooleans(
            "isAffiliate" to AFFILIATE,
            "isMonetized" to MONETIZED,
            "isPartner" to PARTNER,
        )
        channelModelClassDef.overrideBooleans(
            "isAffiliate" to AFFILIATE,
            "isMonetized" to MONETIZED,
            "isPartner" to PARTNER,
            "isParticipatingDJ" to PARTICIPATING_DJ,
        )
        userModelClassDef.overrideBooleans(
            "isAffiliate" to AFFILIATE,
            "isMonetized" to MONETIZED,
            "isPartner" to PARTNER,
            "getHasTurbo" to HAS_TURBO,
            "isEmailVerified" to EMAIL_VERIFIED,
        )
        partialChannelModelClassDef.overrideBooleans(
            "isAffiliate" to AFFILIATE,
            "isMonetized" to MONETIZED,
            "isPartner" to PARTNER,
        )
        channelInfoModClassDef.overrideBooleans(
            "isAffiliate" to AFFILIATE,
            "isClipsCreationEnabled" to CLIPS_CREATION,
        )
        fallbackChannelInfoClassDef.overrideBooleans(
            "isAffiliate" to AFFILIATE,
            "isMonetized" to MONETIZED,
            "isPartner" to PARTNER,
        )

        // Content
        adjacentItemClassDef.overrideBooleans(
            "isMature" to MATURE,
        )
        contentClassificationCategoryModelClassDef.overrideBooleans(
            "isMature" to MATURE,
            "isGambling" to GAMBLING,
            "isRestrictedForCurrentUserAndRegion" to RESTRICTED,
        )

        // Moderation
        moderationActionBundleClassDef.overrideBooleans(
            "getCurrentUserIsBroadcaster" to BROADCASTER,
        )
        chatUserDialogInfoClassDef.overrideBooleans(
            "getCurrentUserIsBroadcaster" to BROADCASTER,
            "getCurrentUserIsMod" to MOD,
        )
        chatRestrictionsStateClassDef.overrideBooleans(
            "getUserIsMod" to MOD,
            "getUserIsVip" to VIP,
            "getUserIsSubscribed" to SUBSCRIBED,
            "getUserIsVerified" to VERIFIED,
            "getUserIsFollowing" to FOLLOWING,
            "getBypassSendChatMessageRestrictions" to BYPASS_RESTRICTIONS,
            "getFollowRequired" to FOLLOW_REQUIRED,
            "getSubscriptionRequired" to SUBSCRIPTION_REQUIRED,
            "getVerificationRequired" to VERIFICATION_REQUIRED,
            "getEmoteOnly" to EMOTE_ONLY,
        )

        // Rewards (visual only)
        communityPointsRewardCustomClassDef.overrideBooleans(
            "isEnabled" to REWARD_ENABLED,
            "isInStock" to REWARD_IN_STOCK,
            "isPaused" to REWARD_PAUSED,
            "isSubOnly" to REWARD_SUB_ONLY,
        )
    }
}
