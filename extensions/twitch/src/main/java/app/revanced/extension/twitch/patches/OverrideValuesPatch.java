package app.revanced.extension.twitch.patches;

import app.revanced.extension.shared.settings.StringSetting;
import app.revanced.extension.twitch.settings.Settings;

@SuppressWarnings("unused")
public class OverrideValuesPatch {
    /**
     * Resolves the override state for a given setting key.
     *
     * @return {@code 1} to force {@code true}, {@code 0} to force {@code false},
     * or {@code -1} to keep the original value.
     */
    public static int getOverride(String key) {
        final StringSetting setting;
        switch (key) {
            case "revanced_override_channel_affiliate": setting = Settings.OVERRIDE_CHANNEL_AFFILIATE; break;
            case "revanced_override_channel_monetized": setting = Settings.OVERRIDE_CHANNEL_MONETIZED; break;
            case "revanced_override_channel_partner": setting = Settings.OVERRIDE_CHANNEL_PARTNER; break;
            case "revanced_override_channel_participating_dj": setting = Settings.OVERRIDE_CHANNEL_PARTICIPATING_DJ; break;
            case "revanced_override_channel_has_turbo": setting = Settings.OVERRIDE_CHANNEL_HAS_TURBO; break;
            case "revanced_override_channel_email_verified": setting = Settings.OVERRIDE_CHANNEL_EMAIL_VERIFIED; break;
            case "revanced_override_channel_clips_creation": setting = Settings.OVERRIDE_CHANNEL_CLIPS_CREATION; break;
            case "revanced_override_content_mature": setting = Settings.OVERRIDE_CONTENT_MATURE; break;
            case "revanced_override_content_gambling": setting = Settings.OVERRIDE_CONTENT_GAMBLING; break;
            case "revanced_override_content_restricted": setting = Settings.OVERRIDE_CONTENT_RESTRICTED; break;
            case "revanced_override_chat_broadcaster": setting = Settings.OVERRIDE_CHAT_BROADCASTER; break;
            case "revanced_override_chat_mod": setting = Settings.OVERRIDE_CHAT_MOD; break;
            case "revanced_override_chat_vip": setting = Settings.OVERRIDE_CHAT_VIP; break;
            case "revanced_override_chat_subscribed": setting = Settings.OVERRIDE_CHAT_SUBSCRIBED; break;
            case "revanced_override_chat_verified": setting = Settings.OVERRIDE_CHAT_VERIFIED; break;
            case "revanced_override_chat_following": setting = Settings.OVERRIDE_CHAT_FOLLOWING; break;
            case "revanced_override_chat_bypass_restrictions": setting = Settings.OVERRIDE_CHAT_BYPASS_RESTRICTIONS; break;
            case "revanced_override_chat_follow_required": setting = Settings.OVERRIDE_CHAT_FOLLOW_REQUIRED; break;
            case "revanced_override_chat_subscription_required": setting = Settings.OVERRIDE_CHAT_SUBSCRIPTION_REQUIRED; break;
            case "revanced_override_chat_verification_required": setting = Settings.OVERRIDE_CHAT_VERIFICATION_REQUIRED; break;
            case "revanced_override_chat_emote_only": setting = Settings.OVERRIDE_CHAT_EMOTE_ONLY; break;
            case "revanced_override_reward_enabled": setting = Settings.OVERRIDE_REWARD_ENABLED; break;
            case "revanced_override_reward_in_stock": setting = Settings.OVERRIDE_REWARD_IN_STOCK; break;
            case "revanced_override_reward_paused": setting = Settings.OVERRIDE_REWARD_PAUSED; break;
            case "revanced_override_reward_sub_only": setting = Settings.OVERRIDE_REWARD_SUB_ONLY; break;
            default: return -1;
        }

        switch (setting.get()) {
            case "true": return 1;
            case "false": return 0;
            default: return -1;
        }
    }
}
