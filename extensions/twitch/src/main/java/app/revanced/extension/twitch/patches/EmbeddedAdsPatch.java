package app.revanced.extension.twitch.patches;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.twitch.settings.Settings;

@SuppressWarnings("unused")
public class EmbeddedAdsPatch {
    public static String proxyManifest(String originalManifest, String tokenJson) {
        if (!Settings.BLOCK_EMBEDDED_ADS.get() || tokenJson == null || originalManifest == null) {
            return originalManifest;
        }

        try {
            String channel = extractChannel(tokenJson);
            if (channel == null) {
                return originalManifest;
            }

            String host = Settings.EMBEDDED_ADS_PROXY_HOST.get();
            if (host == null || host.isEmpty()) {
                return originalManifest;
            }

            String url = "https://" + host + "/live/" + URLEncoder.encode(channel, "UTF-8")
                    + "?allow_source=true&fast_bread=true";

            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            try {
                int code = connection.getResponseCode();
                if (code != 200) {
                    Logger.printDebug(() -> "Embedded ads proxy returned HTTP " + code + ", using original manifest");
                    return originalManifest;
                }

                StringBuilder builder = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        builder.append(line).append('\n');
                    }
                }

                String proxied = builder.toString();
                if (!proxied.startsWith("#EXTM3U")) {
                    return originalManifest;
                }

                Logger.printDebug(() -> "Embedded ads proxy used for channel " + channel);
                return proxied;
            } finally {
                connection.disconnect();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Embedded ads proxy failed, using original manifest", ex);
            return originalManifest;
        }
    }

    private static String extractChannel(String tokenJson) {
        String key = "\"channel\":\"";
        int start = tokenJson.indexOf(key);
        if (start < 0) {
            return null;
        }

        start += key.length();
        int end = tokenJson.indexOf('"', start);
        if (end < 0) {
            return null;
        }

        return tokenJson.substring(start, end);
    }
}
