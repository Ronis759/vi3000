package top.vi3000.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String TAG = "VI3000";
    private static final String START_URL = "https://vi3000.top/";
    private static final Pattern APP_JS_PATTERN = Pattern.compile("/app\\.[a-zA-Z0-9]+\\.js");
    private static final String DISABLE_ADS_JS =
            "(function(){try{"
                    + "window.lampa_settings=window.lampa_settings||{};"
                    + "window.lampa_settings.disable_features=window.lampa_settings.disable_features||{};"
                    + "window.lampa_settings.disable_features.ads=true;"
                    + "var q=parseInt(localStorage.getItem('video_quality_default')||'1080',10);"
                    + "if(isNaN(q)||q>1440)localStorage.setItem('video_quality_default','1080');"
                    + "}catch(e){}})();";

    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int STORAGE_PERMISSION_REQUEST = 1002;

    private WebView webView;
    private SwipeRefreshLayout swipe;
    private ProgressBar progress;
    private View errorView;
    private FrameLayout fullscreenContainer;

    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private ValueCallback<Uri[]> filePathCallback;
    private boolean mainFrameFailed = false;

    private String pendingDownloadUrl;
    private String pendingDownloadUserAgent;
    private String pendingDownloadDisposition;
    private String pendingDownloadMime;

    /** Телевизор или ТВ-приставка: управление пультом, без сенсорного режима. */
    private boolean isTelevision;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean settingsFocused;
    private boolean centerLongPressPending;
    private boolean centerLongPressHandled;
    private final Runnable centerLongPressRunnable = () -> {
        if (settingsFocused) {
            centerLongPressHandled = true;
            showAppMenu();
        }
    };
    private boolean backLongPressPending;
    private boolean backLongPressHandled;
    private final Runnable backLongPressRunnable = () -> {
        backLongPressHandled = true;
        showExitDialog();
    };
    private boolean forceUpdateForTest;

    /** Ответы нативного HTTP-моста для Lampa: индекс запроса → тело ответа. */
    private final Map<Integer, String> httpResponses = new ConcurrentHashMap<>();
    private ExecutorService httpExecutor;
    private String userAgent;
    private volatile String currentPageUrl = START_URL;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(getColor(R.color.bg));
        getWindow().setNavigationBarColor(getColor(R.color.bg));

        isTelevision = detectTelevision();
        if (!isTelevision
                && (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && getIntent().getBooleanExtra("force_tv", false)) {
            isTelevision = true;
        }
        forceUpdateForTest = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && getIntent().getBooleanExtra("force_update", false);

        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        swipe = findViewById(R.id.swipe);
        progress = findViewById(R.id.progress);
        errorView = findViewById(R.id.error_view);
        fullscreenContainer = findViewById(R.id.fullscreen_container);

        swipe.setColorSchemeColors(getColor(R.color.brand));
        swipe.setProgressBackgroundColorSchemeColor(getColor(R.color.bg_soft));
        applySystemBarPadding(swipe);
        applySystemBarPadding(errorView);
        swipe.setOnRefreshListener(() -> {
            if (webView.getUrl() == null) {
                loadStartPage();
            } else {
                webView.reload();
            }
        });

        findViewById(R.id.retry).setOnClickListener(v -> {
            hideError();
            loadStartPage();
        });

        configureWebView();

        if (isTelevision) {
            webView.setFocusable(true);
            webView.setFocusableInTouchMode(true);
            webView.requestFocus();
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else if (isNetworkAvailable()) {
            loadStartPage();
        } else {
            showError();
        }

        if (isTelevision) {
            hideSystemUi();
        }

        scheduleUpdateCheck();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (!backLongPressPending) {
                    backLongPressPending = true;
                    handler.postDelayed(backLongPressRunnable, 350);
                }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(backLongPressRunnable);
                if (backLongPressHandled) {
                    backLongPressHandled = false;
                    backLongPressPending = false;
                    return true;
                }
                backLongPressPending = false;
                onBackPressed();
                return true;
            }
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (settingsFocused && !centerLongPressPending) {
                    centerLongPressPending = true;
                    handler.postDelayed(centerLongPressRunnable, 350);
                }
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(centerLongPressRunnable);
                if (centerLongPressHandled) {
                    centerLongPressHandled = false;
                    centerLongPressPending = false;
                    return true;
                }
                centerLongPressPending = false;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    void setSettingsFocus(boolean focused) {
        settingsFocused = focused;
    }

    private void showAppMenu() {
        String[] items = {"Проверить обновление", "О приложении"};
        new AlertDialog.Builder(this)
                .setTitle("VI3000")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        manualUpdateCheck();
                    } else {
                        showAboutDialog();
                    }
                })
                .show();
    }

    private void showAboutDialog() {
        String version;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            version = "?";
        }
        new AlertDialog.Builder(this)
                .setTitle("VI3000")
                .setMessage("Версия " + version + "\n\n"
                        + "Оболочка для vi3000.top, оптимизированная для Android TV.\n\n"
                        + "Исходный код: github.com/Ronis759/vi3000")
                .setPositiveButton("OK", null)
                .show();
    }

    private String currentVersionName() {
        try {
            String version = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            return version == null ? "" : version;
        } catch (Exception e) {
            return "";
        }
    }

    private void manualUpdateCheck() {
        Toast.makeText(this, "Проверяем обновления…", Toast.LENGTH_SHORT).show();
        new UpdateChecker(currentVersionName(), new UpdateChecker.Listener() {
            @Override
            public void onUpdateAvailable(String version, String releaseUrl, String apkUrl) {
                showUpdateDialog(version, releaseUrl, apkUrl);
            }

            @Override
            public void onNoUpdate() {
                Toast.makeText(MainActivity.this, "У вас актуальная версия", Toast.LENGTH_LONG).show();
            }

            @Override
            public void onCheckError() {
                Toast.makeText(MainActivity.this, "Не удалось проверить обновление", Toast.LENGTH_LONG).show();
            }
        }).check(forceUpdateForTest);
    }

    private void scheduleUpdateCheck() {
        SharedPreferences prefs = getSharedPreferences("vi3000_updates", MODE_PRIVATE);
        long lastCheck = prefs.getLong("last_check", 0L);
        if (!forceUpdateForTest
                && System.currentTimeMillis() - lastCheck < 6L * 60L * 60L * 1000L) {
            return;
        }
        prefs.edit().putLong("last_check", System.currentTimeMillis()).apply();

        new UpdateChecker(currentVersionName(), new UpdateChecker.Listener() {
            @Override
            public void onUpdateAvailable(String version, String releaseUrl, String apkUrl) {
                String skipped = prefs.getString("skipped_version", "");
                if (!forceUpdateForTest && version.equals(skipped)) {
                    return;
                }
                showUpdateDialog(version, releaseUrl, apkUrl);
            }

            @Override
            public void onNoUpdate() {
            }

            @Override
            public void onCheckError() {
            }
        }).check(forceUpdateForTest);
    }

    private void showUpdateDialog(String version, String releaseUrl, String apkUrl) {
        SharedPreferences prefs = getSharedPreferences("vi3000_updates", MODE_PRIVATE);
        new AlertDialog.Builder(this)
                .setTitle("Доступно обновление")
                .setMessage("Версия " + version + " уже выложена на GitHub.\n\n"
                        + "Нажмите «Скачать и установить», чтобы обновиться.")
                .setPositiveButton("Скачать и установить", (d, which) -> {
                    prefs.edit().putString("skipped_version", version).apply();
                    new UpdateInstaller(this).downloadAndInstall(apkUrl, version);
                })
                .setNegativeButton("Позже", (d, which) -> {
                    prefs.edit().putString("skipped_version", version).apply();
                    webView.requestFocus();
                })
                .setOnCancelListener(d -> {
                    prefs.edit().putString("skipped_version", version).apply();
                    webView.requestFocus();
                })
                .show();
    }

    private void openRelease(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
                return;
            }
        } catch (ActivityNotFoundException ignored) {
        }
        new AlertDialog.Builder(this)
                .setTitle("Ссылка на обновление")
                .setMessage(url)
                .setPositiveButton("OK", null)
                .show();
    }

    private boolean detectTelevision() {
        Configuration configuration = getResources().getConfiguration();
        int uiMode = configuration.uiMode & Configuration.UI_MODE_TYPE_MASK;
        if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true;
        }
        PackageManager manager = getPackageManager();
        if (manager.hasSystemFeature("android.hardware.type.television")) {
            return true;
        }
        boolean leanback = manager.hasSystemFeature(PackageManager.FEATURE_LEANBACK);
        boolean touch = manager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
        if (touch) {
            return false;
        }
        int screenLayout = configuration.screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK;
        return leanback || screenLayout >= Configuration.SCREENLAYOUT_SIZE_LARGE;
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setDefaultTextEncodingName("utf-8");

        String userAgent = settings.getUserAgentString();
        if (userAgent != null) {
            userAgent = userAgent.replace("; wv", "").replace(" wv", "");
            // ВАЖНО: НЕ добавляем lampa_client в UA. Сайт распознаёт этот маркер
            // как "нативный клиент" и направляет ВСЕ запросы через httpReq —
            // из-за этого не находятся источники фильмов. Обычный WebView-UA
            // даёт то же поведение сети, что и PWA/браузер.
            userAgent = userAgent.replace("lampa_client/3.3.15", "").trim();
            settings.setUserAgentString(userAgent);
            this.userAgent = userAgent;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        webView.setBackgroundColor(getColor(R.color.bg));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.addJavascriptInterface(new AndroidBridge(this), "AndroidJS");

        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri == null || uri.getHost() == null
                        || !uri.getHost().equalsIgnoreCase("vi3000.top")) {
                    return super.shouldInterceptRequest(view, request);
                }
                String path = uri.getPath();
                if (path == null || !APP_JS_PATTERN.matcher(path).matches()) {
                    return super.shouldInterceptRequest(view, request);
                }
                try {
                    byte[] original = fetchResource(uri.toString());
                    if (original == null) {
                        return super.shouldInterceptRequest(view, request);
                    }
                    String source = new String(original, StandardCharsets.UTF_8);
                    byte[] patched = (DISABLE_ADS_JS + source).getBytes(StandardCharsets.UTF_8);
                    Log.d(TAG, "ads disabled in " + path);
                    return new WebResourceResponse(
                            "application/javascript", "utf-8",
                            new ByteArrayInputStream(patched));
                } catch (Exception e) {
                    Log.w(TAG, "JS intercept failed", e);
                    return super.shouldInterceptRequest(view, request);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUri(request.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUri(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                mainFrameFailed = false;
                if (url != null) {
                    currentPageUrl = url;
                }
                hideError();
                showProgress(5);
                injectShellSettings(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                hideProgress();
                swipe.setRefreshing(false);
                if (!mainFrameFailed) {
                    hideError();
                }
                if (isTelevision) {
                    view.requestFocus();
                }
                injectSettingsFocusWatcher(view);
                injectQualityPatch(view);
                if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                    view.evaluateJavascript(
                            "(function(){try{return document.body.className+' | player='+localStorage.getItem('player')"
                                    + "+' nav='+localStorage.getItem('navigation_type')"
                                    + "+' mobile='+localStorage.getItem('is_true_mobile')}catch(e){return 'err'}})()",
                            value -> Log.d(TAG, "page state: " + value));
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    mainFrameFailed = true;
                    Log.w(TAG, "main frame error: " + error.getDescription());
                    showError();
                }
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                String current = view.getUrl();
                if (failingUrl != null
                        && (failingUrl.equals(current) || failingUrl.equals(START_URL))) {
                    mainFrameFailed = true;
                    showError();
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) {
                handler.cancel();
                showError();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                Toast.makeText(MainActivity.this, R.string.error_title, Toast.LENGTH_LONG).show();
                recreate();
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                showProgress(newProgress);
                if (newProgress >= 100) {
                    hideProgress();
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }

                try {
                    startActivityForResult(Intent.createChooser(intent, getString(R.string.file_chooser_title)),
                            FILE_CHOOSER_REQUEST);
                    return true;
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    return false;
                }
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                fullscreenContainer.setVisibility(View.VISIBLE);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                hideSystemUi();
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                List<String> granted = new ArrayList<>();
                for (String resource : request.getResources()) {
                    if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(resource)) {
                        granted.add(resource);
                    }
                }
                if (granted.isEmpty()) {
                    request.deny();
                } else {
                    request.grant(granted.toArray(new String[0]));
                }
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                          Message resultMsg) {
                WebView temporary = new WebView(MainActivity.this);
                temporary.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        openWindowUrl(request.getUrl(), temporary);
                        return true;
                    }

                    @SuppressWarnings("deprecation")
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, String url) {
                        openWindowUrl(Uri.parse(url), temporary);
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(temporary);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
                if (consoleMessage.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                    Log.e(TAG, "JS: " + consoleMessage.message() + " ("
                            + consoleMessage.sourceId() + ":" + consoleMessage.lineNumber() + ")");
                }
                return true;
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                if (isVideoUrl(url)) {
                    openPlayer(url, null);
                } else {
                    requestDownload(url, userAgent, contentDisposition, mimeType);
                }
            }
        });
    }

    private void openWindowUrl(Uri uri, WebView temporary) {
        if (uri == null || temporary == null) {
            return;
        }
        if (handleUri(uri)) {
            temporary.destroy();
        } else {
            temporary.destroy();
            webView.loadUrl(uri.toString());
        }
    }

    private void loadStartPage() {
        showProgress(5);
        webView.loadUrl(START_URL);
    }

    /**
     * Ставим настройки оболочки до запуска скриптов сайта.
     * На ТВ включаем управление пультом, на телефоне/планшете — сенсорный режим Lampa.
     */
    private byte[] fetchResource(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (userAgent != null && !userAgent.isEmpty()) {
            connection.setRequestProperty("User-Agent", userAgent);
        }
        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies != null && !cookies.isEmpty()) {
            connection.setRequestProperty("Cookie", cookies);
        }
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            InputStream input = connection.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            input.close();
            return output.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    private void injectQualityPatch(WebView view) {
        String js = "(function(){"
                + "if(window.__vi3000QualityPatch)return;window.__vi3000QualityPatch=true;"
                + "function patch(){try{"
                + "if(!window.Lampa||!Lampa.Player||!Lampa.Player.play||Lampa.Player.__qpatch)return;"
                + "var original=Lampa.Player.play;"
                + "Lampa.Player.play=function(data){"
                + "try{"
                + "if(data&&data.quality&&typeof data.quality==='object'){"
                + "var premium=(data.premium_quality||[]).map(String);"
                + "var keys=Object.keys(data.quality).filter(function(k){"
                + "var n=parseInt(k,10);var it=data.quality[k];"
                + "var p=it&&typeof it==='object'&&it.premium;"
                + "return !isNaN(n)&&n<2160&&premium.indexOf(k)<0"
                + "&&premium.indexOf(String(n))<0&&!p;"
                + "}).sort(function(a,b){return parseInt(b,10)-parseInt(a,10);});"
                + "if(keys.length){"
                + "var best=keys[0];var it=data.quality[best];"
                + "var url=(it&&typeof it==='object')?it.url:(typeof it==='string'?it:null);"
                + "if(url)data.url=url;"
                + "try{Lampa.Storage.set('video_quality_default',parseInt(best,10));}catch(e){}"
                + "delete data.premium_start;"
                + "}"
                + "}"
                + "}catch(e){}"
                + "return original.apply(this,arguments);"
                + "};"
                + "Lampa.Player.__qpatch=true;"
                + "}catch(e){}}"
                + "patch();setInterval(patch,1000);"
                + "})();";
        view.evaluateJavascript(js, null);
    }

    private void injectSettingsFocusWatcher(WebView view) {
        String js = "(function(){"
                + "if(window.__vi3000SettingsWatcher)return;"
                + "window.__vi3000SettingsWatcher=true;"
                + "function report(){try{"
                + "if(!window.AndroidJS||!AndroidJS.onSettingsFocus)return;"
                + "var el=document.querySelector('.head__action.open--settings');"
                + "var on=!!(el&&(el.classList.contains('focus')||el===document.activeElement));"
                + "AndroidJS.onSettingsFocus(on);"
                + "}catch(e){}}"
                + "var obs=new MutationObserver(report);"
                + "obs.observe(document.documentElement,{attributes:true,subtree:true,attributeFilter:['class']});"
                + "setInterval(report,600);report();"
                + "})();";
        view.evaluateJavascript(js, null);
    }

    private void injectShellSettings(WebView view) {
        boolean hasTouch = getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
        StringBuilder js = new StringBuilder("(function(){try{");
        js.append("localStorage.setItem('__vi3000_shell__','1');");
        if (isTelevision) {
            js.append("if(!localStorage.getItem('__vi3000_tv_setup__')){");
            js.append("localStorage.setItem('navigation_type','controll');");
            js.append("localStorage.removeItem('is_true_mobile');");
            js.append("localStorage.setItem('__vi3000_tv_setup__','1');");
            js.append("}");
        } else if (hasTouch) {
            js.append("if(!localStorage.getItem('navigation_type')){");
            js.append("localStorage.setItem('navigation_type','touch');");
            js.append("localStorage.setItem('is_true_mobile','true');");
            js.append("}");
        }
        js.append("}catch(e){}})();");
        view.evaluateJavascript(js.toString(), null);
    }

    private boolean handleUri(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);

        if (scheme.equals("http") || scheme.equals("https")) {
            if (isVideoUrl(uri.toString())) {
                openPlayer(uri.toString(), null);
                return true;
            }
            if (isTrustedHost(uri.getHost())) {
                return false;
            }
            openExternal(uri);
            return true;
        }

        if (scheme.equals("about") || scheme.equals("javascript") || scheme.equals("data")) {
            return false;
        }

        if (scheme.equals("intent")) {
            try {
                Intent intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                toast(R.string.app_not_found);
            }
            return true;
        }

        if (scheme.equals("lampa")) {
            if (uri.toString().contains("exit")) {
                finish();
            }
            return true;
        }

        if (scheme.equals("magnet")) {
            openMagnet(uri);
            return true;
        }

        openExternal(uri);
        return true;
    }

    static boolean isTrustedHost(String host) {
        if (host == null) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        return host.equals("vi3000.top") || host.endsWith(".vi3000.top");
    }

    static boolean isVideoUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("blob:") || lower.startsWith("data:")) {
            return false;
        }
        int query = lower.indexOf('?');
        String path = query >= 0 ? lower.substring(0, query) : lower;
        return path.endsWith(".m3u8")
                || path.endsWith(".mpd")
                || path.endsWith(".mp4")
                || path.endsWith(".m4v")
                || path.endsWith(".mkv")
                || path.endsWith(".webm")
                || path.endsWith(".mov")
                || path.endsWith(".avi")
                || path.endsWith(".flv")
                || path.endsWith(".ts")
                || path.endsWith(".mp3")
                || path.endsWith(".aac")
                || lower.contains("|component=hls");
    }

    void openPlayer(String url, String title) {
        if (url == null || url.isEmpty()) {
            return;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return;
        }
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_URL, url);
        if (title != null) {
            intent.putExtra(PlayerActivity.EXTRA_TITLE, title);
        }
        String referer = webView != null ? webView.getUrl() : null;
        if (referer != null) {
            intent.putExtra(PlayerActivity.EXTRA_REFERER, referer);
        }
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast(R.string.app_not_found);
        }
    }

    void openYoutube(String idOrUrl) {
        if (idOrUrl == null || idOrUrl.isEmpty()) {
            return;
        }
        String url = idOrUrl;
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://www.youtube.com/watch?v=" + Uri.encode(idOrUrl);
        }
        openExternal(Uri.parse(url));
    }

    void openExternal(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast(R.string.app_not_found);
        }
    }

    void openMagnet(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast(R.string.app_not_found);
        }
    }

    /**
     * Нативные HTTP-запросы для Lampa (AndroidJS.httpReq/getResp).
     * Сайт на Android не использует jQuery.ajax, а ждёт ответ через мост.
     */
    void httpRequest(String json, int index) {
        try {
            if (httpExecutor == null) {
                httpExecutor = Executors.newFixedThreadPool(4);
            }
            final String requestUserAgent = userAgent;
            final String baseUrl = currentPageUrl;
            httpExecutor.execute(() -> {
                HttpResult result;
                try {
                    result = executeHttp(json, requestUserAgent, baseUrl);
                } catch (Exception e) {
                    Log.w(TAG, "httpReq failed", e);
                    result = HttpResult.error(500, String.valueOf(e.getMessage()));
                }
                httpResponses.put(index, result.body);
                final boolean success = result.success;
                runOnUiThread(() -> {
                    if (webView != null) {
                        webView.evaluateJavascript(
                                "if(window.Lampa&&Lampa.Android){Lampa.Android.httpCall(" + index
                                        + ",'" + (success ? "complite" : "error") + "')}", null);
                    }
                });
            });
        } catch (Throwable t) {
            Log.e(TAG, "httpReq setup failed", t);
            httpResponses.put(index, HttpResult.error(500, String.valueOf(t.getMessage())).body);
            runOnUiThread(() -> {
                if (webView != null) {
                    webView.evaluateJavascript(
                            "if(window.Lampa&&Lampa.Android){Lampa.Android.httpCall(" + index
                                    + ",'error')}", null);
                }
            });
        }
    }

    String takeHttpResponse(int index) {
        String response = httpResponses.remove(index);
        return response == null ? "" : response;
    }

    private HttpResult executeHttp(String json, String userAgent, String baseUrl) throws Exception {
        JSONObject params = new JSONObject(json);
        String url = params.optString("url", "");
        if (url.isEmpty()) {
            return HttpResult.error(404, "Empty url");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            String base = baseUrl == null || baseUrl.isEmpty() ? START_URL : baseUrl;
            url = new URL(new URL(base), url).toString();
        }

        String method = params.optString("type", params.has("post_data") ? "POST" : "GET")
                .toUpperCase(Locale.ROOT);
        String postData = params.has("post_data") && !params.isNull("post_data")
                ? params.optString("post_data", null) : null;
        int timeout = Math.max(1000, params.optInt("timeout", 30000));
        JSONObject headers = params.optJSONObject("headers");

        for (int redirect = 0; redirect < 5; redirect++) {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Accept", "*/*");
            if (userAgent != null && !userAgent.isEmpty()) {
                connection.setRequestProperty("User-Agent", userAgent);
            }
            if (headers != null) {
                Iterator<String> keys = headers.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    String value = headers.optString(key, "");
                    if (!value.isEmpty()) {
                        connection.setRequestProperty(key, value);
                    }
                }
            }
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null && !cookies.isEmpty()) {
                connection.setRequestProperty("Cookie", cookies);
            }

            if (postData != null && !method.equals("GET") && !method.equals("HEAD")) {
                byte[] bytes = postData.getBytes(StandardCharsets.UTF_8);
                if (connection.getRequestProperty("Content-Type") == null) {
                    String trimmed = postData.trim();
                    connection.setRequestProperty("Content-Type",
                            trimmed.startsWith("{") || trimmed.startsWith("[")
                                    ? "application/json; charset=utf-8"
                                    : "application/x-www-form-urlencoded; charset=utf-8");
                }
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }

            int code = connection.getResponseCode();
            saveCookies(url, connection);

            if (code >= 300 && code < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) {
                    return HttpResult.error(code, "Redirect without location");
                }
                url = new URL(new URL(url), location).toString();
                if (code == 303
                        || ((code == 301 || code == 302) && method.equals("POST"))) {
                    method = "GET";
                    postData = null;
                }
                continue;
            }

            InputStream stream = code >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            String text = stream == null ? "" : readStream(stream, connection.getContentType());
            connection.disconnect();

            if (code >= 200 && code < 300) {
                return HttpResult.success(text);
            }
            return HttpResult.error(code, text);
        }
        return HttpResult.error(508, "Too many redirects");
    }

    private static void saveCookies(String url, HttpURLConnection connection) {
        for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
            if (entry.getKey() == null || !entry.getKey().equalsIgnoreCase("Set-Cookie")
                    || entry.getValue() == null) {
                continue;
            }
            for (String cookie : entry.getValue()) {
                CookieManager.getInstance().setCookie(url, cookie);
            }
        }
        CookieManager.getInstance().flush();
    }

    private static String readStream(InputStream stream, String contentType) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = stream.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        stream.close();

        String charset = "UTF-8";
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                part = part.trim();
                if (part.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                    charset = part.substring(8).trim().replace("\"", "");
                }
            }
        }
        try {
            return buffer.toString(charset);
        } catch (Exception e) {
            return buffer.toString("UTF-8");
        }
    }

    private static final class HttpResult {
        final boolean success;
        final String body;

        HttpResult(boolean success, String body) {
            this.success = success;
            this.body = body;
        }

        static HttpResult success(String body) {
            return new HttpResult(true, body == null ? "" : body);
        }

        static HttpResult error(int status, String message) {
            JSONObject object = new JSONObject();
            try {
                object.put("status", status);
                object.put("responseText", message == null ? "" : message);
            } catch (JSONException ignored) {
            }
            return new HttpResult(false, object.toString());
        }
    }

    private void requestDownload(String url, String userAgent, String contentDisposition, String mimeType) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingDownloadUrl = url;
            pendingDownloadUserAgent = userAgent;
            pendingDownloadDisposition = contentDisposition;
            pendingDownloadMime = mimeType;
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    STORAGE_PERMISSION_REQUEST);
            return;
        }
        startDownload(url, userAgent, contentDisposition, mimeType);
    }

    private void startDownload(String url, String userAgent, String contentDisposition, String mimeType) {
        try {
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setMimeType(mimeType);
            request.setTitle(fileName);
            request.setDescription(getString(R.string.app_name));
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);

            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) {
                request.addRequestHeader("Cookie", cookies);
            }
            if (userAgent != null) {
                request.addRequestHeader("User-Agent", userAgent);
            }

            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager != null) {
                manager.enqueue(request);
                toast(R.string.download_started);
            } else {
                toast(R.string.download_error);
            }
        } catch (Exception e) {
            Log.e(TAG, "download error", e);
            toast(R.string.download_error);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == STORAGE_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && pendingDownloadUrl != null) {
                startDownload(pendingDownloadUrl, pendingDownloadUserAgent,
                        pendingDownloadDisposition, pendingDownloadMime);
            } else {
                toast(R.string.download_error);
            }
            pendingDownloadUrl = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST) {
            return;
        }
        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                results = new Uri[count];
                for (int i = 0; i < count; i++) {
                    results[i] = data.getClipData().getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
            return;
        }
        if (webView != null) {
            webView.evaluateJavascript(
                    "(function(){try{"
                            + "if(window.Lampa&&Lampa.Controller){Lampa.Controller.back();return 'controller';}"
                            + "return 'none';"
                            + "}catch(e){return 'error';}})()",
                    value -> {
                        if (value != null && value.contains("controller")) {
                            return;
                        }
                        runOnUiThread(() -> {
                            if (webView.canGoBack()) {
                                webView.goBack();
                            } else {
                                showExitDialog();
                            }
                        });
                    });
            return;
        }
        showExitDialog();
    }

    private void showExitDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.exit_title)
                .setMessage(R.string.exit_text)
                .setPositiveButton(R.string.exit_yes, (dialog, which) -> finish())
                .setNegativeButton(R.string.exit_no, null)
                .show();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            webView.saveState(outState);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) {
            webView.onPause();
        }
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
            httpExecutor = null;
        }
        if (webView != null) {
            fullscreenContainer.removeAllViews();
            webView.removeJavascriptInterface("AndroidJS");
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            if (webView.getParent() instanceof ViewGroup) {
                ((ViewGroup) webView.getParent()).removeView(webView);
            }
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && (customView != null || isTelevision)) {
            hideSystemUi();
        }
    }

    /**
     * Android 15+ принудительно включает edge-to-edge. Добавляем отступы
     * системных панелей, чтобы шапка сайта не уезжала под часы и вырез камеры.
     */
    private void applySystemBarPadding(View view) {
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars()
                                | android.view.WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(left, top, right, bottom);

            if (v == swipe) {
                ViewGroup.MarginLayoutParams params =
                        (ViewGroup.MarginLayoutParams) progress.getLayoutParams();
                if (params.topMargin != top) {
                    params.topMargin = top;
                    progress.setLayoutParams(params);
                }
            }
            return insets;
        });
        view.requestApplyInsets();
    }

    private void hideCustomView() {
        if (customView == null) {
            return;
        }
        fullscreenContainer.removeView(customView);
        customView = null;
        fullscreenContainer.setVisibility(View.GONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showSystemUi();
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
    }

    private void hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.WindowInsetsController controller =
                    getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(android.view.WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                return;
            }
        }
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void showSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.WindowInsetsController controller =
                    getWindow().getInsetsController();
            if (controller != null) {
                controller.show(android.view.WindowInsets.Type.systemBars());
                return;
            }
        }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void showProgress(int value) {
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(Math.max(0, Math.min(100, value)));
    }

    private void hideProgress() {
        progress.setVisibility(View.GONE);
    }

    private void showError() {
        hideProgress();
        swipe.setRefreshing(false);
        errorView.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        errorView.setVisibility(View.GONE);
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return true;
        }
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }

    boolean isTrustedPage() {
        if (webView == null) {
            return false;
        }
        String url = webView.getUrl();
        return url != null && isTrustedHost(Uri.parse(url).getHost());
    }
}
