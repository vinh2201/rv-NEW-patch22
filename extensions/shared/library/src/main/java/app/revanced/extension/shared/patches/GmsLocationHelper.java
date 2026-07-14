package app.revanced.extension.shared.patches;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * Provides location updates directly via Android's LocationManager,
 * bypassing the GmsCore/microG FusedLocationProvider which doesn't work
 * reliably when real Google Play Services is also installed.
 *
 * Registers for ALL available providers (GPS, Network, Fused) to get
 * the best possible location — equivalent to what FusedLocationProvider does.
 * The real Play Services' FLP is available via LocationManager as the "fused" provider
 * even though the patched app can't talk to it directly via the GMS API.
 *
 * @noinspection unused
 */
public class GmsLocationHelper {

    private static final List<LocationListener> activeListeners = new ArrayList<>();
    private static Method onLocationChangedMethod;

    /**
     * Injection point. Called instead of FusedLocationProviderClient.requestLocationUpdates().
     *
     * @param gmsLocationListener The GMS LocationListener (has onLocationChanged(Location))
     *                            passed as Object to avoid compile-time dependency on GMS classes.
     * @param looper              The Looper to receive callbacks on.
     */
    @SuppressLint("MissingPermission")
    public static void requestLocationUpdates(Object gmsLocationListener, Looper looper) {
        try {
            Context context = Utils.getContext();
            if (context == null) {
                Logger.printInfo(() -> "GmsLocationHelper: Context is null, cannot request location updates");
                return;
            }

            LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null) {
                Logger.printInfo(() -> "GmsLocationHelper: LocationManager is null");
                return;
            }

            // Remove any previous listeners first.
            removeLocationUpdatesInternal(locationManager);

            // Cache the reflection method lookup.
            if (onLocationChangedMethod == null) {
                onLocationChangedMethod = gmsLocationListener.getClass()
                        .getMethod("onLocationChanged", Location.class);
            }

            // Bridge GMS LocationListener to Android LocationListener.
            LocationListener listener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    try {
                        onLocationChangedMethod.invoke(gmsLocationListener, location);
                    } catch (Exception e) {
                        Logger.printException(() -> "GmsLocationHelper: Failed to forward location", e);
                    }
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {
                }

                @Override
                public void onProviderEnabled(String provider) {
                }

                @Override
                public void onProviderDisabled(String provider) {
                }
            };

            // Register for all available location providers.
            // This includes GPS, Network (WiFi/Cell), and the system's Fused provider
            // (which is backed by real Google Play Services when installed).
            List<String> providers = locationManager.getProviders(true);
            boolean registered = false;
            for (String provider : providers) {
                try {
                    locationManager.requestLocationUpdates(
                            provider,
                            1000L,
                            0f,
                            listener,
                            looper
                    );
                    registered = true;
                    final String p = provider;
                    Logger.printInfo(() -> "GmsLocationHelper: Registered for provider: " + p);
                } catch (Exception e) {
                    final String p = provider;
                    Logger.printInfo(() -> "GmsLocationHelper: Failed to register for provider: " + p);
                }
            }

            if (registered) {
                activeListeners.add(listener);
            }

            Logger.printInfo(() -> "GmsLocationHelper: Started location updates via LocationManager"
                    + " (providers: " + providers + ")");
        } catch (Exception e) {
            Logger.printException(() -> "GmsLocationHelper: Failed to request location updates", e);
        }
    }

    /**
     * Injection point. Called instead of FusedLocationProviderClient.removeLocationUpdates().
     */
    public static void removeLocationUpdates() {
        try {
            Context context = Utils.getContext();
            if (context == null) return;

            LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null) return;

            removeLocationUpdatesInternal(locationManager);

            Logger.printInfo(() -> "GmsLocationHelper: Stopped location updates");
        } catch (Exception e) {
            Logger.printException(() -> "GmsLocationHelper: Failed to remove location updates", e);
        }
    }

    private static void removeLocationUpdatesInternal(LocationManager locationManager) {
        for (LocationListener listener : activeListeners) {
            try {
                locationManager.removeUpdates(listener);
            } catch (Exception e) {
                // Ignore.
            }
        }
        activeListeners.clear();
    }
}
