package app.revanced.extension.strava;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import app.revanced.extension.shared.Utils;

public final class AddRouteExportPatch {

    public static void showExportDialog(Activity activity) {
        try {
            Intent intent = activity.getIntent();
            if (intent == null) return;
            
            Bundle extras = intent.getExtras();
            if (extras == null) return;
            
            String routeId = null;
            
            for (String key : extras.keySet()) {
                Object val = extras.get(key);
                if (val != null) {
                    String strVal = val.toString();
                    
                    if (val.getClass().getName().contains("SavedRoute")) {
                        try {
                            for (java.lang.reflect.Field field : val.getClass().getDeclaredFields()) {
                                field.setAccessible(true);
                                if (field.getType() == long.class) {
                                    long id = field.getLong(val);
                                    if (id > 0) {
                                        routeId = String.valueOf(id);
                                        break;
                                    }
                                }
                            }
                        } catch (Exception e) {}
                    }
                    
                    if (routeId != null) break;
                    
                    Matcher m = Pattern.compile("routes/([0-9]+)").matcher(strVal);
                    if (m.find()) {
                        routeId = m.group(1);
                        break;
                    }
                }
            }
            
            if (routeId != null) {
                final String finalRouteId = routeId;
                final String finalUrl = "https://www.strava.com/routes/" + finalRouteId;
                
                // On attend 500ms pour laisser le BottomSheet de Strava s'ouvrir,
                // puis on affiche notre popup PAR-DESSUS !
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (activity.isDestroyed() || activity.isFinishing()) return;
                    
                    new AlertDialog.Builder(activity)
                        .setTitle("Options de partage")
                        .setMessage("Voulez-vous exporter cet itinéraire ou utiliser le menu de partage ?")
                        .setPositiveButton("Export GPX", (dialog, which) -> {
                            Utils.openLink(finalUrl + "/export_gpx");
                            activity.finish();
                        })
                        .setNeutralButton("Export TCX", (dialog, which) -> {
                            Utils.openLink(finalUrl + "/export_tcx");
                            activity.finish();
                        })
                        .setNegativeButton("Partager", (dialog, which) -> {
                            // On ferme juste le popup, le menu de partage Strava est déjà affiché en dessous !
                        })
                        .show();
                }, 500);
            }
        } catch (Exception e) {
            // Ignorer les erreurs silencieusement pour éviter les crashs sur d'autres types de partages
        }
    }
}
