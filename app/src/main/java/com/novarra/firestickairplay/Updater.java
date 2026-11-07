package com.novarra.firestickairplay;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Self-updater. Every build published by the GitHub workflow is uploaded to the "latest"
 * release together with version.json; the app compares that with its own version and, on
 * request, downloads the APK and hands it to the system installer.
 */
public final class Updater {

    private static final String TAG = "Updater";
    private static final String BASE_URL =
            "https://github.com/Novarra-Cloud/firestick-airplay/releases/download/latest/";
    static final String ACTION_INSTALL_RESULT = "com.novarra.firestickairplay.INSTALL_RESULT";

    public interface Callback {
        void onUpdateAvailable(String versionName);

        void onUpdateProgress(int percent);

        void onUpdateFailed(String message);
    }

    private final Context context;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean busy;

    public Updater(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    public long installedVersionCode() {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return (Build.VERSION.SDK_INT >= 28) ? info.getLongVersionCode() : info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    public String installedVersionName() {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    /** Checks GitHub for a newer build; calls onUpdateAvailable on the main thread if found. */
    public void check() {
        if (busy) {
            return;
        }
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject(new String(download(BASE_URL + "version.json"), "UTF-8"));
                long latest = json.getLong("versionCode");
                String name = json.optString("versionName", String.valueOf(latest));
                Log.i(TAG, "installed " + installedVersionCode() + ", latest " + latest);
                if (latest > installedVersionCode()) {
                    main.post(() -> callback.onUpdateAvailable(name));
                }
            } catch (Exception e) {
                Log.w(TAG, "update check failed: " + e);
            }
        }, "UpdateCheck").start();
    }

    /** Downloads the newest APK and starts the system install (the TV asks to confirm). */
    public void install() {
        if (busy) {
            return;
        }
        busy = true;
        new Thread(() -> {
            PackageInstaller.Session session = null;
            try {
                PackageInstaller installer = context.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(context.getPackageName());
                int id = installer.createSession(params);
                session = installer.openSession(id);

                HttpURLConnection conn = open(BASE_URL + "FirestickAirPlay.apk");
                long total = conn.getContentLengthLong();
                try (InputStream in = conn.getInputStream();
                     OutputStream out = session.openWrite("app.apk", 0, total > 0 ? total : -1)) {
                    byte[] buf = new byte[64 * 1024];
                    long done = 0;
                    int lastPercent = -1;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int percent = (int) (done * 100 / total);
                            if (percent != lastPercent) {
                                lastPercent = percent;
                                main.post(() -> callback.onUpdateProgress(percent));
                            }
                        }
                    }
                    session.fsync(out);
                } finally {
                    conn.disconnect();
                }

                Intent intent = new Intent(ACTION_INSTALL_RESULT)
                        .setPackage(context.getPackageName())
                        .setClass(context, InstallResultReceiver.class);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pi = PendingIntent.getBroadcast(context, id, intent, flags);
                session.commit(pi.getIntentSender());
                session.close();
                session = null;
            } catch (Exception e) {
                Log.e(TAG, "update failed", e);
                if (session != null) {
                    session.abandon();
                }
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                main.post(() -> callback.onUpdateFailed(msg));
            } finally {
                busy = false;
            }
        }, "UpdateInstall").start();
    }

    private static HttpURLConnection open(String url) throws IOException {
        // GitHub release downloads redirect (https -> https), which HttpURLConnection follows.
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "FirestickAirPlay-Updater");
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new IOException("HTTP " + code + " for " + url);
        }
        return conn;
    }

    private static byte[] download(String url) throws IOException {
        HttpURLConnection conn = open(url);
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            conn.disconnect();
        }
    }
}
