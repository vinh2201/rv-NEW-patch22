package app.revanced.extension.twitch.settings;

import static app.revanced.extension.twitch.Utils.getDrawableId;
import static app.revanced.extension.twitch.Utils.getStringId;

import android.app.Activity;
import android.app.FragmentManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.twitch.settings.preference.TwitchPreferenceFragment;

import tv.twitch.android.settings.SettingsActivity;

@SuppressWarnings({"deprecation", "unused"})
public class TwitchActivityHook {
    private static final String EXTRA_REVANCED_SETTINGS = "app.revanced.twitch.settings";
    public static final String EXTRA_REVANCED_SCREEN = "app.revanced.twitch.settings.screen";
    private static final int REVANCED_FRAGMENT_CONTAINER_ID = View.generateViewId();

    /**
     * Launches SettingsActivity and show ReVanced settings.
     */
    public static void startSettingsActivity() {
        Logger.printDebug(() -> "Launching ReVanced settings");

        final var context = Utils.getContext();
        Logger.printDebug(() -> "startSettingsActivity context=" + context);

        if (context != null) {
            Intent intent = new Intent(context, SettingsActivity.class);
            intent.putExtra(EXTRA_REVANCED_SETTINGS, true);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Logger.printDebug(() -> "startSettingsActivity launched SettingsActivity");
        } else {
            Logger.printDebug(() -> "startSettingsActivity aborted, context is null");
        }
    }

    public static View wrapSettingsView(View settingsView) {
        Logger.printInfo(() -> "wrapSettingsView called, settingsView=" + settingsView);
        try {
            Context context = settingsView.getContext();
            Logger.printDebug(() -> "wrapSettingsView context=" + context);
            LayoutInflater inflater = LayoutInflater.from(context);

            LinearLayout wrapper = new LinearLayout(context);
            wrapper.setOrientation(LinearLayout.VERTICAL);

            int itemLayoutId = Utils.getResourceIdentifier(ResourceType.LAYOUT, "settings_menu_item");
            Logger.printDebug(() -> "wrapSettingsView settings_menu_item id=" + itemLayoutId);
            View item = inflater.inflate(itemLayoutId, wrapper, false);

            int titleId = Utils.getResourceIdentifier(ResourceType.ID, "menu_item_title");
            TextView title = item.findViewById(titleId);
            Logger.printDebug(() -> "wrapSettingsView menu_item_title id=" + titleId + " view=" + title);
            title.setText(getStringId("revanced_settings"));

            int iconId = Utils.getResourceIdentifier(ResourceType.ID, "icon");
            ImageView icon = item.findViewById(iconId);
            Logger.printDebug(() -> "wrapSettingsView icon id=" + iconId + " view=" + icon);
            icon.setImageResource(getDrawableId("ic_settings"));

            item.setOnClickListener(v -> {
                Logger.printDebug(() -> "ReVanced settings entry clicked");
                openRevancedSettings(v.getContext());
            });

            wrapper.addView(item);
            wrapper.addView(settingsView,
                    new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

            Logger.printDebug(() -> "wrapSettingsView added ReVanced entry, returning wrapper");
            return wrapper;
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to add ReVanced settings entry", ex);
            return settingsView;
        }
    }

    public static void openRevancedSettings(Context context) {
        startRevancedSettings(context, null);
    }

    public static void openRevancedScreen(Context context, String screenKey) {
        startRevancedSettings(context, screenKey);
    }

    private static void startRevancedSettings(Context context, String screenKey) {
        Logger.printInfo(() -> "startRevancedSettings called, context=" + context + " screen=" + screenKey);
        try {
            Activity activity = findActivity(context);

            if (activity instanceof SettingsActivity) {
                Logger.printInfo(() -> "startRevancedSettings already in SettingsActivity, loading directly");
                loadRevancedFragment(activity, screenKey);
                return;
            }

            Context launchContext = (activity != null) ? activity : Utils.getContext();
            if (launchContext == null) {
                Logger.printDebug(() -> "startRevancedSettings aborted, no context");
                return;
            }

            Intent intent = new Intent(launchContext, SettingsActivity.class);
            intent.putExtra(EXTRA_REVANCED_SETTINGS, true);
            if (screenKey != null) {
                intent.putExtra(EXTRA_REVANCED_SCREEN, screenKey);
            }
            if (activity == null) {
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }

            launchContext.startActivity(intent);
            Logger.printDebug(() -> "startRevancedSettings launched SettingsActivity");
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to open ReVanced settings", ex);
        }
    }

    private static Activity findActivity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            current = ((ContextWrapper) current).getBaseContext();
        }
        return null;
    }

    public static boolean handleSettingsCreation(Activity base) {
        boolean requested = base.getIntent().getBooleanExtra(EXTRA_REVANCED_SETTINGS, false);
        Logger.printInfo(() -> "handleSettingsCreation called, base=" + base + " requested=" + requested);

        if (!requested) {
            Logger.printDebug(() -> "handleSettingsCreation ReVanced settings not requested");
            return false;
        }

        String screenKey = base.getIntent().getStringExtra(EXTRA_REVANCED_SCREEN);
        loadRevancedFragment(base, screenKey);
        return true;
    }

    /**
     * Shows the ReVanced preference fragment in its own overlay, on top of whatever the
     * activity is currently displaying. Posted so it runs after the activity has finished
     * loading its own screen.
     */
    private static void loadRevancedFragment(Activity base, String screenKey) {
        new Handler(Looper.getMainLooper()).post(() -> {
            Logger.printInfo(() -> "loadRevancedFragment started, base=" + base);
            try {
                ViewGroup contentRoot = base.findViewById(android.R.id.content);
                if (contentRoot == null) {
                    Logger.printInfo(() -> "loadRevancedFragment aborted, no content root");
                    return;
                }

                FragmentManager legacyFragmentManager = base.getFragmentManager();
                Logger.printInfo(() -> "loadRevancedFragment legacy FragmentManager=" + legacyFragmentManager);
                if (legacyFragmentManager == null) {
                    Logger.printInfo(() -> "loadRevancedFragment aborted, legacy FragmentManager unavailable");
                    return;
                }

                if (legacyFragmentManager.findFragmentById(REVANCED_FRAGMENT_CONTAINER_ID) != null) {
                    Logger.printDebug(() -> "loadRevancedFragment already showing, ignoring");
                    return;
                }

                FrameLayout overlay = contentRoot.findViewById(REVANCED_FRAGMENT_CONTAINER_ID);
                if (overlay == null) {
                    overlay = new FrameLayout(base);
                    overlay.setId(REVANCED_FRAGMENT_CONTAINER_ID);
                    overlay.setOnApplyWindowInsetsListener((v, insets) -> {
                        v.setPadding(
                                v.getPaddingLeft(),
                                insets.getSystemWindowInsetTop(),
                                v.getPaddingRight(),
                                insets.getSystemWindowInsetBottom());
                        return insets;
                    });
                    contentRoot.addView(overlay, new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    overlay.requestApplyInsets();
                    Logger.printDebug(() -> "loadRevancedFragment created overlay container");
                }
                final FrameLayout overlayContainer = overlay;

                TwitchPreferenceFragment fragment = new TwitchPreferenceFragment();
                if (screenKey != null) {
                    Bundle arguments = new Bundle();
                    arguments.putString(EXTRA_REVANCED_SCREEN, screenKey);
                    fragment.setArguments(arguments);
                }

                legacyFragmentManager.addOnBackStackChangedListener(new FragmentManager.OnBackStackChangedListener() {
                    @Override
                    public void onBackStackChanged() {
                        if (legacyFragmentManager.getBackStackEntryCount() == 0) {
                            contentRoot.removeView(overlayContainer);
                            legacyFragmentManager.removeOnBackStackChangedListener(this);
                            Logger.printDebug(() -> "loadRevancedFragment overlay removed");
                        }
                    }
                });

                legacyFragmentManager.beginTransaction()
                        .setCustomAnimations(
                                android.R.animator.fade_in, android.R.animator.fade_out,
                                android.R.animator.fade_in, android.R.animator.fade_out)
                        .add(REVANCED_FRAGMENT_CONTAINER_ID, fragment)
                        .addToBackStack(null)
                        .commit();
                legacyFragmentManager.executePendingTransactions();
                Logger.printInfo(() -> "loadRevancedFragment committed, fragment.getView()=" + fragment.getView());

                View fragmentView = fragment.getView();
                if (fragmentView != null) {
                    TypedValue typedValue = new TypedValue();
                    int color = 0xFF000000;
                    if (base.getTheme().resolveAttribute(android.R.attr.colorBackground, typedValue, true)) {
                        color = typedValue.data | 0xFF000000;
                    }
                    final int background = color;
                    fragmentView.setBackgroundColor(background);
                    Logger.printDebug(() -> "loadRevancedFragment set fragment background=#" + Integer.toHexString(background));
                } else {
                    Logger.printDebug(() -> "loadRevancedFragment fragment view null");
                }

                Logger.printDebug(() -> "loadRevancedFragment added RV fragment to overlay");
            } catch (Exception ex) {
                Logger.printException(() -> "loadRevancedFragment failed to load fragment", ex);
            }
        });
    }
}
