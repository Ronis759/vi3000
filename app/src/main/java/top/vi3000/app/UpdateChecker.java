package top.vi3000.app;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Тихая проверка наличия новой версии через GitHub Releases API. */
public final class UpdateChecker {

    private static final String TAG = "VI3000Update";
    private static final String API =
            "https://api.github.com/repos/Ronis759/vi3000/releases/latest";
    private static final String SAFE_PREFIX =
            "https://github.com/Ronis759/vi3000/releases/";
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final Handler UI = new Handler(Looper.getMainLooper());

    public interface Listener {
        void onUpdateAvailable(String version, String releaseUrl, String apkUrl);

        void onNoUpdate();

        void onCheckError();
    }

    private final Listener listener;
    private final String currentVersion;

    public UpdateChecker(String currentVersion, Listener listener) {
        this.currentVersion = currentVersion;
        this.listener = listener;
    }

    /**
     * @param forceVersion показывать обновление даже если версия не старше текущей
     *                     (только для теста в debug-сборке)
     */
    public void check(boolean forceVersion) {
        POOL.execute(() -> {
            try {
                String body = request();
                JSONObject json = new JSONObject(body);
                String tag = json.optString("tag_name", "");
                String releaseUrl = json.optString("html_url", "");
                if (tag.isEmpty() || releaseUrl.isEmpty()) {
                    UI.post(listener::onCheckError);
                    return;
                }
                String version = tag.replaceFirst("^[vV]", "").trim();
                String apkUrl = extractApkUrl(json);

                if (!forceVersion && compare(version, currentVersion) <= 0) {
                    UI.post(listener::onNoUpdate);
                    return;
                }
                UI.post(() -> listener.onUpdateAvailable(version, releaseUrl, apkUrl));
            } catch (Exception e) {
                Log.d(TAG, "update check failed: " + e.getMessage());
                UI.post(listener::onCheckError);
            }
        });
    }

    private static String extractApkUrl(JSONObject json) {
        JSONArray assets = json.optJSONArray("assets");
        if (assets == null) {
            return "";
        }
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) {
                continue;
            }
            String url = asset.optString("browser_download_url", "");
            String name = asset.optString("name", "");
            if (!name.toLowerCase().endsWith(".apk")) {
                continue;
            }
            // Качаем только из нашего репозитория по HTTPS.
            if (url.startsWith(SAFE_PREFIX)) {
                return url;
            }
        }
        return "";
    }

    private static String request() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(API).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "VI3000-AndroidTV");
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            InputStream stream = connection.getInputStream();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
            reader.close();
            return result.toString();
        } finally {
            connection.disconnect();
        }
    }

    private static int[] numbers(String value) {
        String clean = value.replaceAll("[^0-9.]", "");
        String[] parts = clean.split("\\.");
        int[] result = new int[3];
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try {
                result[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    private static int compare(String left, String right) {
        int[] a = numbers(left);
        int[] b = numbers(right);
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return a[i] > b[i] ? 1 : -1;
            }
        }
        return 0;
    }
}
