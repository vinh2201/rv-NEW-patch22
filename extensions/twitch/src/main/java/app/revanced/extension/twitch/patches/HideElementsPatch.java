package app.revanced.extension.twitch.patches;

import android.view.View;
import android.view.ViewTreeObserver;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.shared.settings.BooleanSetting;
import app.revanced.extension.twitch.settings.Settings;

@SuppressWarnings("unused")
public class HideElementsPatch {
    private static final Set<View> hookedRoots =
            Collections.newSetFromMap(new WeakHashMap<>());

    public static void hideElements(View view) {
        if (view == null || !anyEnabled()) {
            return;
        }

        view.post(() -> {
            View root = view.getRootView();
            if (root == null) {
                return;
            }

            hideAll(root);

            if (hookedRoots.add(root)) {
                ViewTreeObserver observer = root.getViewTreeObserver();
                if (observer.isAlive()) {
                    observer.addOnGlobalLayoutListener(() -> hideAll(root));
                    Logger.printDebug(() -> "Attached hide-elements layout listener to root");
                }
            }
        });
    }

    private static void hideAll(View root) {
        hide(root, Settings.HIDE_GIFT_LEADERBOARDS, "leaderboards_with_extension_header");
        hide(root, Settings.HIDE_BITS_BUTTON, "bit_picker");
        hide(root, Settings.HIDE_EMOTES_BUTTON, "emoticon_picker");
        hide(root, Settings.HIDE_CHAT_HEADER, "chat_header_container");
        hide(root, Settings.HIDE_COMMUNITY_POINTS_BUTTON, "community_points_button_container");
        hide(root, Settings.HIDE_CHAT_RESTRICTIONS, "chat_restrictions_container");
        hide(root, Settings.HIDE_AD_CONTAINER,
                "ad_container", "ad_app_install_container", "audio_ads_container",
                "ads_metadata_container", "ad_paused_overlay_container");
        hide(root, Settings.HIDE_CLIP_BUTTON, "create_clip_text_button");
        hide(root, Settings.HIDE_CAST_BUTTON, "cast_button");
        hide(root, Settings.HIDE_SHARE_BUTTON, "share_button");
        hideParentOf(root, Settings.HIDE_SUBSCRIBE_FOLLOW_BAR, "follow_subscribe_button");
        hide(root, Settings.HIDE_UPSELL_BANNERS,
                "turbo_upsell_container", "turbo_signup_upsell_container",
                "viewer_account_turbo_upsell", "toolbar_upsell_button",
                "prime_subscribe_button", "turbo_subscribe_button",
                "gift_a_sub_button", "promo_bits_button");
        hide(root, Settings.HIDE_STORIES,
                "stories_shelf_container", "stories_button", "all_stories_button");
        hide(root, Settings.HIDE_PREDICTIONS,
                "prediction_highlight_container", "predictions_tab_layout");
        hide(root, Settings.HIDE_POLLS, "poll_items_container");
        hide(root, Settings.HIDE_GOALS, "goal_contribution_container", "goal_item_container");
        hide(root, Settings.HIDE_DROPS, "drops_container");
        hide(root, Settings.HIDE_CELEBRATIONS,
                "celebrations_container", "celebrations_overlay_container");
        hide(root, Settings.HIDE_RAIDS, "raid_container", "raid_message_container");
        hide(root, Settings.HIDE_WHISPERS, "whisper_button");
        hide(root, Settings.HIDE_TOOLTIPS, "tooltip_container", "callout_layout");
        hide(root, Settings.HIDE_PBYP_AD, "ads_pbyp_portrait_container");
        hide(root, Settings.HIDE_VIEWER_COUNT, "channel_viewer_count", "broadcast_viewer_count");
    }

    private static boolean anyEnabled() {
        return Settings.HIDE_GIFT_LEADERBOARDS.get()
                || Settings.HIDE_BITS_BUTTON.get()
                || Settings.HIDE_EMOTES_BUTTON.get()
                || Settings.HIDE_CHAT_HEADER.get()
                || Settings.HIDE_COMMUNITY_POINTS_BUTTON.get()
                || Settings.HIDE_CHAT_RESTRICTIONS.get()
                || Settings.HIDE_AD_CONTAINER.get()
                || Settings.HIDE_CLIP_BUTTON.get()
                || Settings.HIDE_CAST_BUTTON.get()
                || Settings.HIDE_SHARE_BUTTON.get()
                || Settings.HIDE_SUBSCRIBE_FOLLOW_BAR.get()
                || Settings.HIDE_UPSELL_BANNERS.get()
                || Settings.HIDE_STORIES.get()
                || Settings.HIDE_PREDICTIONS.get()
                || Settings.HIDE_POLLS.get()
                || Settings.HIDE_GOALS.get()
                || Settings.HIDE_DROPS.get()
                || Settings.HIDE_CELEBRATIONS.get()
                || Settings.HIDE_RAIDS.get()
                || Settings.HIDE_WHISPERS.get()
                || Settings.HIDE_TOOLTIPS.get()
                || Settings.HIDE_PBYP_AD.get()
                || Settings.HIDE_VIEWER_COUNT.get();
    }

    private static void hideParentOf(View root, BooleanSetting setting, String resourceName) {
        if (!setting.get()) {
            return;
        }

        int id = Utils.getResourceIdentifier(ResourceType.ID, resourceName);
        if (id == 0) {
            return;
        }

        View view = root.findViewById(id);
        if (view == null) {
            return;
        }

        View target = (view.getParent() instanceof View) ? (View) view.getParent() : view;
        if (target != root && target.getVisibility() != View.GONE) {
            target.setVisibility(View.GONE);
            Logger.printDebug(() -> "Hid bar (parent of " + resourceName + ")");
        }
    }

    private static void hide(View root, BooleanSetting setting, String... resourceNames) {
        if (!setting.get()) {
            return;
        }

        for (String resourceName : resourceNames) {
            int id = Utils.getResourceIdentifier(ResourceType.ID, resourceName);
            if (id == 0) {
                continue;
            }

            View view = root.findViewById(id);
            if (view != null && view.getVisibility() != View.GONE) {
                view.setVisibility(View.GONE);
                Logger.printDebug(() -> "Hid element: " + resourceName);
            }
        }
    }
}
