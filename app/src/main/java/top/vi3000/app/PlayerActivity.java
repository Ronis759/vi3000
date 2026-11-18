package top.vi3000.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Встроенный плеер для ссылок, которые сайт обычно открывает во внешнем
 * Android-плеере (прямые mp4/m3u8-ссылки, торрент-потоки TorrServer и т.п.).
 */
public class PlayerActivity extends Activity {

    static final String EXTRA_URL = "url";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_REFERER = "referer";

    private static final String TAG = "VI3000Player";

    private ExoPlayer player;
    private PlayerView playerView;
    private View errorView;
    private TextView errorText;
    private String url;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        setContentView(R.layout.activity_player);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        playerView = findViewById(R.id.player_view);
        errorView = findViewById(R.id.player_error_view);
        errorText = findViewById(R.id.player_error_text);
        Button retry = findViewById(R.id.player_retry);
        retry.setOnClickListener(v -> {
            hideError();
            prepare();
        });

        url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null || url.isEmpty()) {
            finish();
            return;
        }

        createPlayer();
        prepare();
        hideSystemUi();
    }

    private void createPlayer() {
        String userAgent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", userAgent);
        String referer = getIntent().getStringExtra(EXTRA_REFERER);
        if (referer != null
                && (referer.startsWith("http://") || referer.startsWith("https://"))) {
            headers.put("Referer", referer);
            headers.put("Origin", originOf(referer));
        }
        try {
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null && !cookies.isEmpty()) {
                headers.put("Cookie", cookies);
            }
        } catch (Exception ignored) {
        }

        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000)
                .setDefaultRequestProperties(headers);

        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(httpFactory);

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(mediaSourceFactory)
                .build();
        player.setHandleAudioBecomingNoisy(true);
        playerView.setPlayer(player);
        playerView.requestFocus();

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    hideError();
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                showError(error);
            }
        });
    }

    private void prepare() {
        if (player == null) {
            return;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        MediaItem.Builder builder = new MediaItem.Builder().setUri(Uri.parse(url));
        if (lower.contains(".m3u8")) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8);
        } else if (lower.contains(".mpd")) {
            builder.setMimeType(MimeTypes.APPLICATION_MPD);
        }
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title != null) {
            builder.setMediaMetadata(new MediaMetadata.Builder().setTitle(title).build());
        }
        player.setMediaItem(builder.build());
        player.setPlayWhenReady(true);
        player.prepare();
    }

    private static String originOf(String url) {
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme != null && host != null) {
                return scheme + "://" + host;
            }
        } catch (Exception ignored) {
        }
        return "https://vi3000.top";
    }

    private void showError(PlaybackException error) {
        errorView.setVisibility(View.VISIBLE);
        String details = error == null ? "" : error.getErrorCodeName();
        errorText.setText(getString(R.string.player_error)
                + (details.isEmpty() ? "" : "\n" + details));
    }

    private void hideError() {
        errorView.setVisibility(View.GONE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) {
            player.setPlayWhenReady(false);
        }
    }

    @Override
    protected void onDestroy() {
        if (player != null) {
            player.release();
            player = null;
        }
        playerView.setPlayer(null);
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    private void hideSystemUi() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
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
}
