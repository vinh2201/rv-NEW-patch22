package app.revanced.extension.twitch.patches;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.twitch.settings.Settings;


@SuppressWarnings("unused")
public final class AdSuppressionMonitor {

    private static final String TAG = "AdSuppressionMonitor";
    private static final long STATUS_INTERVAL_MS = 60_000L;

    private static final String[] AD_VIEW_NAME_FRAGMENTS = {
            "ad_overlay_frame",
            "ad_player_frame",
            "audio_ads_container",
            "ad_metadata",
            "sponsored_stream",
            "display_ad",
    };

    public enum Status {
        CLEAN,
        LEAK_DETECTED,   // event-counter or ui overlay tripped
        NETWORK_LEAK,    // ad-network call observed (highest seveerity)
        UNKNOWN          // monitor not initealized
    }

    private static final AtomicReference<Status> CURRENT_STATUS =
            new AtomicReference<>(Status.UNKNOWN);
    private static final Map<String, AtomicInteger> EVENT_COUNTS =
            new ConcurrentHashMap<>();
    private static final AtomicInteger UI_OVERLAY_COUNT = new AtomicInteger();
    private static final AtomicInteger NETWORK_AD_COUNT = new AtomicInteger();

    private static volatile boolean initialized;
    private static volatile int initRetries;
    private static Handler statusHandler;

    private AdSuppressionMonitor() {}


    public static void init() {
        if (initialized) return;

        Context ctx = Utils.getContext();
        if (!(ctx instanceof Application)) {
            if (initRetries++ < 20) {
                new Handler(Looper.getMainLooper()).postDelayed(
                        AdSuppressionMonitor::init, 50L);
            }
            return;
        }

        try {
            if (!Settings.AD_SUPPRESSION_MONITOR_ENABLED.get()) {
                Logger.printDebug(() -> TAG + " disabled via settings");
                return;
            }
        } catch (Throwable t) {
            if (initRetries++ < 20) {
                new Handler(Looper.getMainLooper()).postDelayed(
                        AdSuppressionMonitor::init, 50L);
            }
            return;
        }

        synchronized (AdSuppressionMonitor.class) {
            if (initialized) return;
            initialized = true;
        }

        CURRENT_STATUS.set(Status.CLEAN);

        Application app = (Application) ctx;
        app.registerActivityLifecycleCallbacks(new LifecycleHook());

        statusHandler = new Handler(Looper.getMainLooper());
        statusHandler.postDelayed(STATUS_RUNNABLE, STATUS_INTERVAL_MS);

        Logger.printInfo(() -> "[AD_MONITOR_INIT] active intervalMs="
                + STATUS_INTERVAL_MS);
    }


    public static void onAdEvent(String className, String source) {
        if (!initialized) return;
        AtomicInteger counter = EVENT_COUNTS.get(className);
        if (counter == null) {
            counter = EVENT_COUNTS.computeIfAbsent(className,
                    k -> new AtomicInteger());
        }
        int n = counter.incrementAndGet();
        promote(Status.LEAK_DETECTED);
        long t = System.currentTimeMillis();
        Logger.printInfo(() -> String.format(Locale.ROOT,
                "[AD_EVENT] class=%s source=%s count=%d time=%d",
                className, source == null ? "?" : source, n, t));
    }


    public static void onNetworkAdRequest(String url) {
        if (!initialized || url == null) return;
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("edge.ads.twitch.tv")
                || lower.contains("pubads")
                || lower.contains("adservice")
                || lower.contains("/ads/")
                || lower.contains("/commercial")) {
            int n = NETWORK_AD_COUNT.incrementAndGet();
            promote(Status.NETWORK_LEAK);
            Logger.printInfo(() -> String.format(Locale.ROOT,
                    "[AD_NETWORK] url=%s count=%d time=%d",
                    url, n, System.currentTimeMillis()));
        }
    }
    public static Status getStatus() {
        return CURRENT_STATUS.get();
    }

    private static void promote(Status candidate) {
        while (true) {
            Status cur = CURRENT_STATUS.get();
            if (severity(candidate) <= severity(cur)) return;
            if (CURRENT_STATUS.compareAndSet(cur, candidate)) return;
        }
    }

    private static int severity(Status s) {
        switch (s) {
            case NETWORK_LEAK:  return 3;
            case LEAK_DETECTED: return 2;
            case CLEAN:         return 1;
            default:            return 0;
        }
    }

    private static final Runnable STATUS_RUNNABLE = new Runnable() {
        @Override
        public void run() {
            try {
                Status s = CURRENT_STATUS.get();
                StringBuilder sb = new StringBuilder()
                        .append("[AD_MONITOR_STATUS] ").append(s.name())
                        .append(" uiOverlayHits=").append(UI_OVERLAY_COUNT.get())
                        .append(" networkHits=").append(NETWORK_AD_COUNT.get());
                if (!EVENT_COUNTS.isEmpty()) {
                    sb.append(" events={");
                    boolean first = true;
                    for (Map.Entry<String, AtomicInteger> e
                            : EVENT_COUNTS.entrySet()) {
                        if (!first) sb.append(',');
                        sb.append(e.getKey()).append('=').append(e.getValue().get());
                        first = false;
                    }
                    sb.append('}');
                }
                final String msg = sb.toString();
                Logger.printInfo(() -> msg);
            } finally {
                if (statusHandler != null) {
                    statusHandler.postDelayed(this, STATUS_INTERVAL_MS);
                }
            }
        }
    };

    private static final class LifecycleHook
            implements Application.ActivityLifecycleCallbacks {

        @Override public void onActivityCreated(Activity a, Bundle b) {}
        @Override public void onActivityStarted(Activity a) {}
        @Override public void onActivityPaused(Activity a) {}
        @Override public void onActivityStopped(Activity a) {}
        @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
        @Override public void onActivityDestroyed(Activity a) {}

        @Override
        public void onActivityResumed(Activity activity) {
            try {
                View root = activity.getWindow().getDecorView();
                if (!(root instanceof ViewGroup)) return;
                installRecursively((ViewGroup) root, activity.getResources());
            } catch (Throwable t) {
                Logger.printException(() -> TAG + " lifecycle hook failed", t);
            }
        }

        private void installRecursively(ViewGroup vg, Resources res) {
            // Tag-guard so we never double-attach.
            if (vg.getTag(KEY_TAG) != null) return;
            vg.setTag(KEY_TAG, Boolean.TRUE);

            vg.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                @Override
                public void onChildViewAdded(View parent, View child) {
                    inspect(child, res);
                    if (child instanceof ViewGroup) {
                        installRecursively((ViewGroup) child, res);
                    }
                }

                @Override
                public void onChildViewRemoved(View parent, View child) {
                }
            });




            int n = vg.getChildCount();
            for (int i = 0; i < n; i++) {
                View child = vg.getChildAt(i);
                inspect(child, res);
                if (child instanceof ViewGroup) {
                    installRecursively((ViewGroup) child, res);
                }
            }

            ViewTreeObserver vto = vg.getViewTreeObserver();
            if (vto != null && vto.isAlive()) {
                vto.addOnGlobalLayoutListener(() -> {
                });
            }
        }
        private void inspect(View v, Resources res) {
            int id = v.getId();
            if (id == View.NO_ID) return;
            String name;
            try {
                name = res.getResourceEntryName(id);
            } catch (Resources.NotFoundException ignored) {
                return;
            }
            if (name == null) return;
            String lower = name.toLowerCase(Locale.ROOT);
            for (String frag : AD_VIEW_NAME_FRAGMENTS) {
                if (lower.contains(frag)) {
                    int n = UI_OVERLAY_COUNT.incrementAndGet();
                    promote(Status.LEAK_DETECTED);
                    long t = System.currentTimeMillis();
                    Logger.printInfo(() -> String.format(Locale.ROOT,
                            "[UI_AD_OVERLAY] attached=true id=%s view=%s count=%d time=%d",
                            name, v.getClass().getSimpleName(), n, t));
                    return;
                }
            }
        }
    }

    private static final int KEY_TAG = 0x7f5e0001; 
}
