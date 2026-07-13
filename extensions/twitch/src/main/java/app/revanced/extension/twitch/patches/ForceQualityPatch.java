package app.revanced.extension.twitch.patches;

import com.amazonaws.ivs.player.MediaPlayer;
import com.amazonaws.ivs.player.Quality;

import java.util.Set;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.twitch.settings.Settings;

@SuppressWarnings("unused")
public class ForceQualityPatch {
    public static boolean forceMaxQuality(MediaPlayer player, String requested) {
        if (!Settings.FORCE_MAX_QUALITY.get() || player == null) {
            return false;
        }

        if ("audio_only".equals(requested)) {
            return false;
        }

        Set<Quality> qualities = player.getQualities();
        if (qualities == null || qualities.isEmpty()) {
            return false;
        }

        Quality best = null;
        for (Quality quality : qualities) {
            if (best == null
                    || quality.getHeight() > best.getHeight()
                    || (quality.getHeight() == best.getHeight() && quality.getBitrate() > best.getBitrate())) {
                best = quality;
            }
        }

        if (best == null) {
            return false;
        }

        final Quality selected = best;
        player.setAutoQualityMode(false);
        player.setQuality(selected);
        Logger.printDebug(() -> "Forced max quality: " + selected.getName());
        return true;
    }
}
