package top.vi3000.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Скачивает APK из GitHub и запускает системный установщик. */
public final class UpdateInstaller {

    private static final String TAG = "VI3000Install";
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private final Activity activity;
    private ProgressDialog progress;

    public UpdateInstaller(Activity activity) {
        this.activity = activity;
    }

    public void downloadAndInstall(String apkUrl, String version) {
        if (apkUrl == null || apkUrl.isEmpty()) {
            Toast.makeText(activity, "Не удалось получить ссылку на APK", Toast.LENGTH_LONG).show();
            return;
        }
        if (!canInstall()) {
            showPermissionDialog();
            return;
        }

        POOL.execute(() -> {
            File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) {
                dir = activity.getCacheDir();
            }
            File file = new File(dir, "VI3000-" + version + ".apk");
            try {
                URL url = new URL(apkUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setRequestProperty("User-Agent", "VI3000-AndroidTV");

                int code = connection.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new IOException("HTTP " + code);
                }
                long total = connection.getContentLengthLong();
                UI.post(() -> showProgress(version, total));

                try (InputStream input = connection.getInputStream();
                     OutputStream output = new FileOutputStream(file)) {
                    byte[] buffer = new byte[8192];
                    long done = 0;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                        done += read;
                        final long current = done;
                        UI.post(() -> updateProgress(current, total));
                    }
                }
                connection.disconnect();

                UI.post(() -> {
                    hideProgress();
                    install(file);
                });
            } catch (Exception e) {
                Log.e(TAG, "download failed", e);
                UI.post(() -> {
                    hideProgress();
                    Toast.makeText(activity, "Не удалось скачать обновление", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private boolean canInstall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return activity.getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }

    private void showPermissionDialog() {
        new AlertDialog.Builder(activity)
                .setTitle("Разрешите установку")
                .setMessage("Чтобы обновить VI3000, включите установку приложений "
                        + "из неизвестных источников в настройках системы.")
                .setPositiveButton("Открыть настройки", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(activity, "Не удалось открыть настройки", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showProgress(String version, long total) {
        hideProgress();
        progress = new ProgressDialog(activity);
        progress.setTitle("Загрузка обновления");
        progress.setMessage("Версия " + version);
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100);
        progress.setIndeterminate(total <= 0);
        progress.setCancelable(false);
        progress.show();
    }

    private void updateProgress(long current, long total) {
        if (progress == null || !progress.isShowing()) {
            return;
        }
        if (total > 0) {
            progress.setProgress((int) (current * 100 / total));
        } else {
            progress.setMessage("Загружено " + (current / 1024) + " КБ");
        }
    }

    private void hideProgress() {
        if (progress != null) {
            try {
                progress.dismiss();
            } catch (Exception ignored) {
            }
            progress = null;
        }
    }

    private void install(File file) {
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        Uri uri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            uri = FileProvider.getUriForFile(
                    activity,
                    activity.getPackageName() + ".fileprovider",
                    file);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            uri = Uri.fromFile(file);
        }
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, "Не удалось открыть установщик", Toast.LENGTH_LONG).show();
        }
    }
}
