package app.revanced.extension.youtube.patches;

import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.youtube.settings.Settings;
import app.revanced.extension.youtube.shared.PlayerType;

@SuppressWarnings("unused")
public class DimShortsButtonsPatch {

    /**
     * Injection point.
     */
    public static void dimShortsPlayerOverlay(View shortsOverlayView) {
        shortsOverlayView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            private ViewTreeObserver registeredObserver;
            private ViewTreeObserver.OnPreDrawListener preDrawListener;

            @Override
            public void onViewAttachedToWindow(View v) {
                // reel_watch_player is the video surface; the buttons overlay sits in its parent.
                ViewParent parent = v.getParent();
                final View target = (parent instanceof View) ? (View) parent : v;

                Logger.printDebug(() -> "DimShorts overlay: target=" + target.getClass().getName());

                preDrawListener = () -> {
                    int opacity = Settings.SHORTS_BUTTONS_OPACITY.get();
                    if (opacity < 0 || opacity > 100) opacity = 100;
                    final float alpha = opacity / 100.0f;
                    if (target.getAlpha() != alpha) {
                        target.setAlpha(alpha);
                    }
                    return true;
                };
                registeredObserver = target.getViewTreeObserver();
                registeredObserver.addOnPreDrawListener(preDrawListener);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                if (preDrawListener != null && registeredObserver != null
                        && registeredObserver.isAlive()) {
                    registeredObserver.removeOnPreDrawListener(preDrawListener);
                }
                preDrawListener = null;
                registeredObserver = null;
            }
        });
    }

    /**
     * Injection point.
     */
    public static void dimShortsToolbarButton(String enumString, View buttonView) {
        Logger.printDebug(() -> "DimShorts toolbar: enum=" + enumString);

        buttonView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            private ViewTreeObserver registeredObserver;
            private ViewTreeObserver.OnPreDrawListener preDrawListener;

            @Override
            public void onViewAttachedToWindow(View v) {
                preDrawListener = () -> {
                    final float targetAlpha;
                    if (PlayerType.getCurrent().isNoneOrHidden()) {
                        int opacity = Settings.SHORTS_BUTTONS_OPACITY.get();
                        if (opacity < 0 || opacity > 100) opacity = 100;
                        targetAlpha = opacity / 100.0f;
                    } else {
                        targetAlpha = 1.0f;
                    }
                    if (v.getAlpha() != targetAlpha) {
                        v.setAlpha(targetAlpha);
                    }
                    return true;
                };
                registeredObserver = v.getViewTreeObserver();
                registeredObserver.addOnPreDrawListener(preDrawListener);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                if (preDrawListener != null && registeredObserver != null
                        && registeredObserver.isAlive()) {
                    registeredObserver.removeOnPreDrawListener(preDrawListener);
                }
                preDrawListener = null;
                registeredObserver = null;
            }
        });
    }
}
