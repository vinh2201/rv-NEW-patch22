package app.revanced.extension.twitch.settings;

import app.revanced.extension.shared.settings.BooleanSetting;
import app.revanced.extension.shared.settings.BaseSettings;
import app.revanced.extension.shared.settings.StringSetting;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;

public class Settings extends BaseSettings {
    /* Ads */
    public static final BooleanSetting BLOCK_AUDIO_ADS = new BooleanSetting("revanced_block_audio_ads", TRUE);
    public static final BooleanSetting BLOCK_DISPLAY_ADS = new BooleanSetting("revanced_block_display_ads", TRUE);
    public static final BooleanSetting BLOCK_EMBEDDED_ADS = new BooleanSetting("revanced_block_embedded_ads", FALSE);
    public static final StringSetting EMBEDDED_ADS_PROXY_HOST = new StringSetting("revanced_embedded_ads_proxy_host", "eu.luminous.dev");

    /* Chat */
    public static final BooleanSetting SHOW_DELETED_MESSAGES = new BooleanSetting("revanced_show_deleted_messages", FALSE);
    public static final BooleanSetting AUTO_CLAIM_CHANNEL_POINTS = new BooleanSetting("revanced_auto_claim_channel_points", TRUE);
    public static final BooleanSetting DISABLE_CHAT_HAPTICS = new BooleanSetting("revanced_disable_chat_haptics", TRUE);
    public static final BooleanSetting CHAT_MESSAGE_CUSTOM_PADDING = new BooleanSetting("revanced_chat_message_custom_padding", FALSE);
    public static final StringSetting CHAT_MESSAGE_PADDING_TOP = new StringSetting("revanced_chat_message_padding_top", "9");
    public static final StringSetting CHAT_MESSAGE_PADDING_BOTTOM = new StringSetting("revanced_chat_message_padding_bottom", "9");
    public static final StringSetting CHAT_MESSAGE_PADDING_LEFT = new StringSetting("revanced_chat_message_padding_left", "24");
    public static final StringSetting CHAT_MESSAGE_PADDING_RIGHT = new StringSetting("revanced_chat_message_padding_right", "24");

    /* Misc */
    /**
     * Not to be confused with {@link BaseSettings#DEBUG}.
     */
    public static final BooleanSetting TWITCH_DEBUG_MODE = new BooleanSetting("revanced_twitch_debug_mode", FALSE, true);
    public static final BooleanSetting FORCE_MAX_QUALITY = new BooleanSetting("revanced_force_max_quality", FALSE);
    public static final BooleanSetting DISABLE_RAID_REDIRECT = new BooleanSetting("revanced_disable_raid_redirect", TRUE);

    /* Layout */
    public static final BooleanSetting HIDE_GIFT_LEADERBOARDS = new BooleanSetting("revanced_hide_gift_leaderboards", FALSE);
    public static final BooleanSetting HIDE_BITS_BUTTON = new BooleanSetting("revanced_hide_bits_button", FALSE);
    public static final BooleanSetting HIDE_EMOTES_BUTTON = new BooleanSetting("revanced_hide_emotes_button", FALSE);
    public static final BooleanSetting HIDE_CHAT_HEADER = new BooleanSetting("revanced_hide_chat_header", FALSE);
    public static final BooleanSetting HIDE_COMMUNITY_POINTS_BUTTON = new BooleanSetting("revanced_hide_community_points_button", FALSE);
    public static final BooleanSetting HIDE_CHAT_RESTRICTIONS = new BooleanSetting("revanced_hide_chat_restrictions", FALSE);
    public static final BooleanSetting HIDE_AD_CONTAINER = new BooleanSetting("revanced_hide_ad_container", FALSE);
    public static final BooleanSetting HIDE_CLIP_BUTTON = new BooleanSetting("revanced_hide_clip_button", FALSE);
    public static final BooleanSetting HIDE_CAST_BUTTON = new BooleanSetting("revanced_hide_cast_button", FALSE);
    public static final BooleanSetting HIDE_SHARE_BUTTON = new BooleanSetting("revanced_hide_share_button", FALSE);
    public static final BooleanSetting HIDE_SUBSCRIBE_FOLLOW_BAR = new BooleanSetting("revanced_hide_subscribe_follow_bar", FALSE);
    public static final BooleanSetting HIDE_UPSELL_BANNERS = new BooleanSetting("revanced_hide_upsell_banners", FALSE);
    public static final BooleanSetting HIDE_STORIES = new BooleanSetting("revanced_hide_stories", FALSE);
    public static final BooleanSetting HIDE_PREDICTIONS = new BooleanSetting("revanced_hide_predictions", FALSE);
    public static final BooleanSetting HIDE_POLLS = new BooleanSetting("revanced_hide_polls", FALSE);
    public static final BooleanSetting HIDE_GOALS = new BooleanSetting("revanced_hide_goals", FALSE);
    public static final BooleanSetting HIDE_DROPS = new BooleanSetting("revanced_hide_drops", FALSE);
    public static final BooleanSetting HIDE_CELEBRATIONS = new BooleanSetting("revanced_hide_celebrations", FALSE);
    public static final BooleanSetting HIDE_RAIDS = new BooleanSetting("revanced_hide_raids", FALSE);
    public static final BooleanSetting HIDE_WHISPERS = new BooleanSetting("revanced_hide_whispers", FALSE);
    public static final BooleanSetting HIDE_TOOLTIPS = new BooleanSetting("revanced_hide_tooltips", FALSE);
    public static final BooleanSetting HIDE_PBYP_AD = new BooleanSetting("revanced_hide_pbyp_ad", FALSE);
    public static final BooleanSetting HIDE_VIEWER_COUNT = new BooleanSetting("revanced_hide_viewer_count", FALSE);
    public static final BooleanSetting HIDE_UPSELL_DIALOGS = new BooleanSetting("revanced_hide_upsell_dialogs", FALSE);

    /* Override - Channel */
    public static final StringSetting OVERRIDE_CHANNEL_AFFILIATE = new StringSetting("revanced_override_channel_affiliate", "default");
    public static final StringSetting OVERRIDE_CHANNEL_MONETIZED = new StringSetting("revanced_override_channel_monetized", "default");
    public static final StringSetting OVERRIDE_CHANNEL_PARTNER = new StringSetting("revanced_override_channel_partner", "default");
    public static final StringSetting OVERRIDE_CHANNEL_PARTICIPATING_DJ = new StringSetting("revanced_override_channel_participating_dj", "default");
    public static final StringSetting OVERRIDE_CHANNEL_HAS_TURBO = new StringSetting("revanced_override_channel_has_turbo", "default");
    public static final StringSetting OVERRIDE_CHANNEL_EMAIL_VERIFIED = new StringSetting("revanced_override_channel_email_verified", "default");
    public static final StringSetting OVERRIDE_CHANNEL_CLIPS_CREATION = new StringSetting("revanced_override_channel_clips_creation", "default");

    /* Override - Content */
    public static final StringSetting OVERRIDE_CONTENT_MATURE = new StringSetting("revanced_override_content_mature", "default");
    public static final StringSetting OVERRIDE_CONTENT_GAMBLING = new StringSetting("revanced_override_content_gambling", "default");
    public static final StringSetting OVERRIDE_CONTENT_RESTRICTED = new StringSetting("revanced_override_content_restricted", "default");

    /* Override - Moderation */
    public static final StringSetting OVERRIDE_CHAT_BROADCASTER = new StringSetting("revanced_override_chat_broadcaster", "default");
    public static final StringSetting OVERRIDE_CHAT_MOD = new StringSetting("revanced_override_chat_mod", "default");
    public static final StringSetting OVERRIDE_CHAT_VIP = new StringSetting("revanced_override_chat_vip", "default");
    public static final StringSetting OVERRIDE_CHAT_SUBSCRIBED = new StringSetting("revanced_override_chat_subscribed", "default");
    public static final StringSetting OVERRIDE_CHAT_VERIFIED = new StringSetting("revanced_override_chat_verified", "default");
    public static final StringSetting OVERRIDE_CHAT_FOLLOWING = new StringSetting("revanced_override_chat_following", "default");
    public static final StringSetting OVERRIDE_CHAT_BYPASS_RESTRICTIONS = new StringSetting("revanced_override_chat_bypass_restrictions", "default");
    public static final StringSetting OVERRIDE_CHAT_FOLLOW_REQUIRED = new StringSetting("revanced_override_chat_follow_required", "default");
    public static final StringSetting OVERRIDE_CHAT_SUBSCRIPTION_REQUIRED = new StringSetting("revanced_override_chat_subscription_required", "default");
    public static final StringSetting OVERRIDE_CHAT_VERIFICATION_REQUIRED = new StringSetting("revanced_override_chat_verification_required", "default");
    public static final StringSetting OVERRIDE_CHAT_EMOTE_ONLY = new StringSetting("revanced_override_chat_emote_only", "default");

    /* Override - Rewards */
    public static final StringSetting OVERRIDE_REWARD_ENABLED = new StringSetting("revanced_override_reward_enabled", "default");
    public static final StringSetting OVERRIDE_REWARD_IN_STOCK = new StringSetting("revanced_override_reward_in_stock", "default");
    public static final StringSetting OVERRIDE_REWARD_PAUSED = new StringSetting("revanced_override_reward_paused", "default");
    public static final StringSetting OVERRIDE_REWARD_SUB_ONLY = new StringSetting("revanced_override_reward_sub_only", "default");
}
