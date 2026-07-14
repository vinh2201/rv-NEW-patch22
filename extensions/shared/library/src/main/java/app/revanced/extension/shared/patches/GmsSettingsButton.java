package app.revanced.extension.shared.patches;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * Creates settings and account switch buttons in place of the missing profile avatar.
 * The avatar is missing because GmsCore doesn't implement the InAppReach service.
 *
 * @noinspection unused
 */
public class GmsSettingsButton {

    // Injected by the patch at build time.
    public static String DIALOG_FACTORY_CLASS_NAME;
    public static String DIALOG_FACTORY_METHOD_NAME;
    public static String FRAGMENT_SHOWER_CLASS_NAME;
    public static String FRAGMENT_SHOWER_METHOD_NAME;
    public static String FRAGMENT_SHOWER_PARAM1_CLASS_NAME;
    public static String FRAGMENT_SHOWER_PARAM2_CLASS_NAME;
    public static String SETTINGS_FRAGMENT_CLASS_NAME;

    private static volatile boolean buttonAdded = false;

    /**
     * Injection point. Called at the start of the avatar setup method with
     * the ViewGroup container. Posts a delayed check — if the normal
     * SelectedAccountDisc was not added (InAppReach unavailable), we add
     * our buttons instead.
     *
     * @param viewGroup The avatar container ViewGroup (search_omnibox_one_google_account_disc).
     */
    public static void addSettingsButton(ViewGroup viewGroup) {
        try {
            if (viewGroup == null) {
                return;
            }

            viewGroup.postDelayed(() -> {
                try {
                    if (viewGroup.getVisibility() == View.VISIBLE && viewGroup.getChildCount() > 0) {
                        Logger.printInfo(() -> "GmsSettingsButton: Avatar disc present, skipping");
                        return;
                    }

                    if (buttonAdded) {
                        return;
                    }
                    buttonAdded = true;

                    Context context = viewGroup.getContext();
                    if (context == null) {
                        return;
                    }

                    float density = context.getResources().getDisplayMetrics().density;
                    int buttonSize = (int) (32 * density);
                    int spacing = (int) (4 * density);

                    // Horizontal container for both buttons.
                    LinearLayout container = new LinearLayout(context);
                    container.setOrientation(LinearLayout.HORIZONTAL);
                    container.setGravity(Gravity.CENTER_VERTICAL);

                    // Account switch button (person icon).
                    TextView accountBtn = createCircleButton(context, "\uD83D\uDC64", buttonSize);
                    accountBtn.setOnClickListener(v -> showAccountSwitcher(context));

                    // Settings button (gear icon).
                    TextView settingsBtn = createCircleButton(context, "\u2699", buttonSize);
                    settingsBtn.setOnClickListener(v -> openSettings(context));

                    LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(buttonSize, buttonSize);
                    btnParams.setMarginEnd(spacing);
                    container.addView(accountBtn, btnParams);

                    LinearLayout.LayoutParams btnParams2 = new LinearLayout.LayoutParams(buttonSize, buttonSize);
                    container.addView(settingsBtn, btnParams2);

                    viewGroup.removeAllViews();
                    FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT);
                    containerParams.gravity = Gravity.CENTER;
                    viewGroup.addView(container, containerParams);
                    viewGroup.setVisibility(View.VISIBLE);

                    Logger.printInfo(() -> "GmsSettingsButton: Settings + Account buttons added");
                } catch (Exception e) {
                    Logger.printException(() -> "GmsSettingsButton: Delayed setup failed", e);
                }
            }, 500);
        } catch (Exception e) {
            Logger.printException(() -> "GmsSettingsButton: Failed to add buttons", e);
        }
    }

    private static TextView createCircleButton(Context context, String icon, int size) {
        TextView btn = new TextView(context);
        btn.setText(icon);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        btn.setTextColor(Color.parseColor("#5F6368"));
        btn.setGravity(Gravity.CENTER);
        btn.setClickable(true);
        btn.setFocusable(true);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#E8EAED"));
        bg.setSize(size, size);
        btn.setBackground(bg);

        return btn;
    }

    public static final int ACCOUNT_PICKER_REQUEST = 9271;

    private static void showAccountSwitcher(Context context) {
        try {
            Activity activity = getActivity(context);
            if (activity == null) {
                Logger.printInfo(() -> "GmsSettingsButton: No activity for account switcher");
                return;
            }

            // Use Android's system account picker. The account type "com.google" is
            // rewritten to "app.revanced" by the GmsCore patch at build time.
            String[] accountTypes = new String[]{"com.google"};
            Intent intent = AccountManager.newChooseAccountIntent(
                    null,             // selectedAccount
                    null,             // allowableAccounts
                    accountTypes,     // allowableAccountTypes
                    null,             // descriptionOverrideText
                    null,             // addAccountAuthTokenType
                    null,             // addAccountRequiredFeatures
                    null              // addAccountOptions
            );

            activity.startActivityForResult(intent, ACCOUNT_PICKER_REQUEST);
            Logger.printInfo(() -> "GmsSettingsButton: Launched system account picker");
        } catch (Exception e) {
            Logger.printException(() -> "GmsSettingsButton: Failed to show account picker", e);
        }
    }

    /**
     * Injection point. Called at the start of GmmActivity.onActivityResult().
     * Intercepts the account picker result and triggers Maps' native account switch.
     *
     * @return true if we handled the result (caller should return early), false otherwise.
     */
    public static boolean onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        if (requestCode != ACCOUNT_PICKER_REQUEST) {
            return false;
        }

        Logger.printInfo(() -> "GmsSettingsButton: Account picker result: " + resultCode);

        if (resultCode != Activity.RESULT_OK || data == null) {
            return true; // Handled but cancelled.
        }

        String accountName = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
        if (accountName == null || accountName.isEmpty()) {
            Logger.printInfo(() -> "GmsSettingsButton: No account name in result");
            return true;
        }

        Logger.printInfo(() -> "GmsSettingsButton: Selected account: " + accountName);

        // Trigger Maps' built-in account switch dialog.
        // The dialog factory creates a SWITCH_ACCOUNTS dialog fragment,
        // then the fragment shower displays it.
        try {
            Class<?> dialogClass = Class.forName(DIALOG_FACTORY_CLASS_NAME);
            Method factoryMethod = dialogClass.getDeclaredMethod(DIALOG_FACTORY_METHOD_NAME, int.class, String.class);
            factoryMethod.setAccessible(true);
            Object switchDialog = factoryMethod.invoke(null, 0, accountName);

            Class<?> showerClass = Class.forName(FRAGMENT_SHOWER_CLASS_NAME);
            Class<?> param1Class = Class.forName(FRAGMENT_SHOWER_PARAM1_CLASS_NAME);
            Class<?> param2Class = Class.forName(FRAGMENT_SHOWER_PARAM2_CLASS_NAME);

            Method showFragment = showerClass.getDeclaredMethod(FRAGMENT_SHOWER_METHOD_NAME, param1Class, param2Class);
            showFragment.setAccessible(true);
            showFragment.invoke(null, activity, switchDialog);

            Logger.printInfo(() -> "GmsSettingsButton: Triggered native account switch to: " + accountName);
        } catch (Exception e) {
            Logger.printException(() -> "GmsSettingsButton: Failed to trigger account switch", e);
        }

        return true;
    }

    private static void openSettings(Context context) {
        try {
            Activity activity = getActivity(context);
            if (activity == null) {
                Logger.printInfo(() -> "GmsSettingsButton: No activity to open settings");
                return;
            }

            Class<?> settingsFragmentClass = Class.forName(SETTINGS_FRAGMENT_CLASS_NAME);
            Constructor<?> ctor = settingsFragmentClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object settingsFragment = ctor.newInstance();

            Class<?> showerClass = Class.forName(FRAGMENT_SHOWER_CLASS_NAME);
            Class<?> param1Class = Class.forName(FRAGMENT_SHOWER_PARAM1_CLASS_NAME);
            Class<?> param2Class = Class.forName(FRAGMENT_SHOWER_PARAM2_CLASS_NAME);

            Method showFragment = showerClass.getDeclaredMethod(FRAGMENT_SHOWER_METHOD_NAME, param1Class, param2Class);
            showFragment.setAccessible(true);
            showFragment.invoke(null, activity, settingsFragment);

            Logger.printInfo(() -> "GmsSettingsButton: Opened settings");
        } catch (Exception e) {
            Logger.printException(() -> "GmsSettingsButton: Failed to open settings", e);
        }
    }

    private static Activity getActivity(Context context) {
        // Context is always an Activity since we hook the activity context.
        return (Activity) context;
    }
}
