package top.vi3000.app;

import android.net.Uri;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Мост, который ожидает сайт Lampa на Android (глобальный объект AndroidJS).
 * Версия 3.3-16 включает только те возможности, которые реально реализованы:
 * запуск плеера, YouTube, нативные HTTP-запросы, торрент-ссылки и корректный выход.
 */
public class AndroidBridge {

    private final MainActivity activity;

    AndroidBridge(MainActivity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public String appVersion() {
        return "3.3-16";
    }

    @JavascriptInterface
    public void httpReq(final String data, final int index) {
        activity.httpRequest(data, index);
    }

    /** Сообщаем из страницы, что фокус пульта сейчас на значке настроек в шапке. */
    @JavascriptInterface
    public void onSettingsFocus(boolean focused) {
        activity.setSettingsFocus(focused);
    }

    /** Сайт попытался показать своё окно выхода — показываем окно выхода оболочки. */
    @JavascriptInterface
    public void onSettingsExit() {
        activity.runOnUiThread(activity::exitFromSite);
    }

    @JavascriptInterface
    public String getResp(int index) {
        return activity.takeHttpResponse(index);
    }

    @JavascriptInterface
    public void openPlayer(final String link, final String data) {
        activity.runOnUiThread(() -> {
            if (!activity.isTrustedPage()) {
                return;
            }
            activity.openPlayer(link, extractTitle(data));
        });
    }

    @JavascriptInterface
    public void openTorrentLink(final String link, final String data) {
        activity.runOnUiThread(() -> {
            if (!activity.isTrustedPage() || link == null || link.isEmpty()) {
                return;
            }
            String lower = link.toLowerCase(Locale.ROOT);
            if (lower.startsWith("magnet:")) {
                activity.openMagnet(Uri.parse(link));
            } else if (lower.startsWith("http://") || lower.startsWith("https://")) {
                activity.openPlayer(link, extractTitle(data));
            } else {
                try {
                    activity.openExternal(Uri.parse(link));
                } catch (Exception ignored) {
                }
            }
        });
    }

    @JavascriptInterface
    public void openYoutube(final String link) {
        activity.runOnUiThread(() -> {
            if (activity.isTrustedPage()) {
                activity.openYoutube(link);
            }
        });
    }

    @JavascriptInterface
    public void clearDefaultPlayer() {
        // Ничего не делаем: выбор внешнего плеера в настройках сайта не нужен.
    }

    @JavascriptInterface
    public void exit() {
        activity.runOnUiThread(activity::finish);
    }

    private static String extractTitle(String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        try {
            JSONObject json = new JSONObject(data);
            String title = json.optString("title", "");
            return title.isEmpty() ? null : title;
        } catch (Exception e) {
            return null;
        }
    }
}
