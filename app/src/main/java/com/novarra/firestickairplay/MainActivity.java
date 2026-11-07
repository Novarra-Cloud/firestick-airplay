package com.novarra.firestickairplay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.media3.ui.SubtitleView;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Locale;

/**
 * Full-screen receiver. While this screen is open the Fire TV shows up in the iPhone's
 * Control Center -> Screen Mirroring list; mirrored video is shown here.
 */
public class MainActivity extends Activity implements AirPlayServer.Listener,
        VideoDecoder.SizeListener, SurfaceHolder.Callback, HlsPlayer.Listener, Updater.Callback {

    private static final String PREFS = "airplay";
    private static final String PREF_DEVICE_ID = "device_id";
    private static final String PREF_NAME = "receiver_name";
    private static final String PREF_SUBTITLES = "subtitles";
    private static final int STATUS_READY = 0;
    private static final int STATUS_BUSY = 1;
    private static final int STATUS_ERROR = 2;
    private static final long UPDATE_CHECK_INTERVAL_MS = 60 * 60 * 1000;

    private final VideoDecoder videoDecoder = new VideoDecoder();
    private final AudioPlayer audioPlayer = new AudioPlayer();
    private AirPlayServer server;
    private HlsPlayer hlsPlayer;
    private Updater updater;
    private AlertDialog updateDialog;
    private long lastUpdateCheck;

    private SurfaceView surfaceView;
    private SurfaceView hlsSurfaceView;
    private View idleScreen;
    private TextView nameView;
    private TextView statusView;
    private View statusDot;
    private TextView addressView;
    private TextView toastView;
    private final Runnable hideToast = () -> toastView.setVisibility(View.GONE);

    private WifiManager.MulticastLock multicastLock;
    private WifiManager.WifiLock wifiLock;
    private String receiverName;
    private int videoWidth;
    private int videoHeight;
    private int hlsWidth;
    private int hlsHeight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        surfaceView = findViewById(R.id.video_surface);
        hlsSurfaceView = findViewById(R.id.hls_surface);
        idleScreen = findViewById(R.id.idle_screen);
        nameView = findViewById(R.id.receiver_name);
        statusView = findViewById(R.id.status);
        statusDot = findViewById(R.id.status_dot);
        addressView = findViewById(R.id.address);
        toastView = findViewById(R.id.toast);
        SubtitleView subtitleView = findViewById(R.id.subtitles);

        surfaceView.getHolder().addCallback(this);
        videoDecoder.setSizeListener(this);
        server = new AirPlayServer(videoDecoder, audioPlayer);
        server.setListener(this);
        boolean subtitles = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_SUBTITLES, false);
        hlsPlayer = new HlsPlayer(this, hlsSurfaceView, subtitleView, subtitles, this);
        server.setHlsPlayer(hlsPlayer);

        updater = new Updater(this, this);
        receiverName = loadReceiverName();
        nameView.setText(getString(R.string.receiver_name_label, receiverName));
    }

    @Override
    protected void onStart() {
        super.onStart();
        hideSystemUi();
        acquireNetworkLocks();
        startServer();
        long now = System.currentTimeMillis();
        if (now - lastUpdateCheck > UPDATE_CHECK_INTERVAL_MS) {
            lastUpdateCheck = now;
            updater.check();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // The receiver only runs while this screen is open, so it disappears from the
        // iPhone's list when the app is closed.
        server.stop();
        releaseNetworkLocks();
        showIdle();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    private void startServer() {
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        // Ask the iPhone for a 1080p (or the TV's resolution, if lower) 30/60 fps stream.
        int width = Math.max(dm.widthPixels, dm.heightPixels);
        int height = Math.min(dm.widthPixels, dm.heightPixels);
        if (width > 1920 || height > 1080 || width <= 0 || height <= 0) {
            width = 1920;
            height = 1080;
        }
        String keyFile = new File(getFilesDir(), "airplay_key.pem").getAbsolutePath();
        int result = server.start(receiverName, loadDeviceId(), keyFile, width, height, 30);
        updateAddress();
        if (result == 0) {
            setStatus(getString(R.string.status_ready), STATUS_READY);
        } else {
            setStatus(getString(R.string.status_failed, result), STATUS_ERROR);
        }
    }

    // ------------------------------------------------------------------ AirPlayServer.Listener

    @Override
    public void onClientConnecting(String name, String model) {
        runOnUiThread(() -> setStatus(getString(R.string.status_connecting, name), STATUS_BUSY));
    }

    @Override
    public void onConnectionCount(int count) {
        if (count == 0 && !hlsPlayer.isActive()) {
            runOnUiThread(() -> {
                showIdle();
                setStatus(getString(R.string.status_ready), STATUS_READY);
            });
        }
    }

    @Override
    public void onMirroringStopped() {
        runOnUiThread(() -> {
            if (hlsPlayer.isActive()) {
                return;
            }
            showIdle();
            setStatus(getString(R.string.status_ready), STATUS_READY);
        });
    }

    @Override
    public void onStatus(String message) {
        // Native log lines are only used for troubleshooting (see logcat).
    }

    // ------------------------------------------------------------------ VideoDecoder.SizeListener

    @Override
    public void onVideoSizeChanged(int width, int height) {
        runOnUiThread(() -> {
            videoWidth = width;
            videoHeight = height;
            fitSurface(surfaceView, videoWidth, videoHeight);
        });
    }

    @Override
    public void onFirstFrame() {
        runOnUiThread(() -> {
            if (!hlsPlayer.isActive()) {
                surfaceView.setVisibility(View.VISIBLE);
                idleScreen.setVisibility(View.GONE);
            }
        });
    }

    // ------------------------------------------------------------------ HlsPlayer.Listener (main thread)

    @Override
    public void onAirPlayVideoStarted() {
        setStatus(getString(R.string.status_loading_video), STATUS_BUSY);
        hlsSurfaceView.setVisibility(View.VISIBLE);
    }

    @Override
    public void onAirPlayVideoFirstFrame() {
        if (idleScreen.getVisibility() == View.VISIBLE) {
            idleScreen.setVisibility(View.GONE);
            showToast(getString(R.string.hint_remote), 6000);
        }
    }

    /** Remote control while a handed-over video plays: OK = subtitles, play/pause, skip. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (hlsPlayer.isActive() && idleScreen.getVisibility() != View.VISIBLE) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_CAPTIONS:
                case KeyEvent.KEYCODE_MENU: {
                    String lang = hlsPlayer.toggleSubtitles();
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putBoolean(PREF_SUBTITLES, hlsPlayer.subtitlesEnabled()).apply();
                    if (lang == null) {
                        showToast(getString(R.string.subtitles_none), 3000);
                    } else if (lang.isEmpty()) {
                        showToast(getString(R.string.subtitles_off), 2000);
                    } else {
                        showToast(lang.equals("?") ? getString(R.string.subtitles_on_plain)
                                : getString(R.string.subtitles_on, lang), 2000);
                    }
                    return true;
                }
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    hlsPlayer.togglePause();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    hlsPlayer.seekBy(10_000);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    hlsPlayer.seekBy(-10_000);
                    return true;
                default:
                    break;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void showToast(String text, long millis) {
        toastView.removeCallbacks(hideToast);
        toastView.setText(text);
        toastView.setVisibility(View.VISIBLE);
        toastView.postDelayed(hideToast, millis);
    }

    @Override
    public void onAirPlayVideoSizeChanged(int width, int height) {
        hlsWidth = width;
        hlsHeight = height;
        fitSurface(hlsSurfaceView, hlsWidth, hlsHeight);
    }

    @Override
    public void onAirPlaySubtitlesChanged(boolean enabled, String language, boolean hasTracks) {
        if (!enabled) {
            showToast(getString(R.string.subtitles_off), 2000);
        } else if (!hasTracks) {
            showToast(getString(R.string.subtitles_none), 4000);
        } else {
            showToast(language == null || language.isEmpty()
                    ? getString(R.string.subtitles_on_plain)
                    : getString(R.string.subtitles_on, language), 2000);
        }
    }

    @Override
    public void onAirPlayVideoStopped() {
        hlsSurfaceView.setVisibility(View.GONE);
        showIdle();
        setStatus(getString(R.string.status_ready), STATUS_READY);
    }

    @Override
    public void onAirPlayVideoError(String message) {
        hlsSurfaceView.setVisibility(View.GONE);
        showIdle();
        setStatus(getString(R.string.status_video_error, message), STATUS_ERROR);
    }

    // ------------------------------------------------------------------ SurfaceHolder.Callback

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        videoDecoder.setSurface(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // size changes are handled by the decoder scaling to the surface
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        videoDecoder.setSurface(null);
    }

    // ------------------------------------------------------------------ helpers

    private void setStatus(String text, int kind) {
        statusView.setText(text);
        int color = (kind == STATUS_READY) ? 0xFF34D399 : (kind == STATUS_ERROR) ? 0xFFF87171 : 0xFF5AB0FF;
        statusDot.getBackground().mutate().setTint(color);
    }

    private void showIdle() {
        idleScreen.setVisibility(View.VISIBLE);
    }

    /** Letterboxes the video so it keeps its aspect ratio (portrait phones get side bars). */
    private void fitSurface(SurfaceView view, int videoWidth, int videoHeight) {
        View parent = (View) view.getParent();
        int pw = parent.getWidth();
        int ph = parent.getHeight();
        if (pw <= 0 || ph <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            return;
        }
        int w = pw;
        int h = (int) ((long) pw * videoHeight / videoWidth);
        if (h > ph) {
            h = ph;
            w = (int) ((long) ph * videoWidth / videoHeight);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            view.setLayoutParams(lp);
        }
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private void acquireNetworkLocks() {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) {
            return;
        }
        // Without this lock many devices drop the multicast packets used for discovery.
        if (multicastLock == null) {
            multicastLock = wifi.createMulticastLock("airplay-mdns");
            multicastLock.setReferenceCounted(false);
        }
        multicastLock.acquire();
        if (wifiLock == null) {
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "airplay-stream");
            wifiLock.setReferenceCounted(false);
        }
        wifiLock.acquire();
    }

    private void releaseNetworkLocks() {
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
    }

    // ------------------------------------------------------------------ Updater.Callback

    @Override
    public void onUpdateAvailable(String versionName) {
        // Don't interrupt something that is playing; it will be offered next time.
        if (isFinishing() || idleScreen.getVisibility() != View.VISIBLE) {
            lastUpdateCheck = 0;
            return;
        }
        if (updateDialog != null && updateDialog.isShowing()) {
            return;
        }
        updateDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.update_title)
                .setMessage(getString(R.string.update_message, versionName))
                .setPositiveButton(R.string.update_now, (d, w) -> startUpdate())
                .setNegativeButton(R.string.update_later, null)
                .show();
    }

    @Override
    public void onUpdateProgress(int percent) {
        setStatus(getString(R.string.update_downloading, percent), STATUS_BUSY);
    }

    @Override
    public void onUpdateFailed(String message) {
        setStatus(getString(R.string.update_failed, message), STATUS_ERROR);
    }

    private void startUpdate() {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.update_permission_title)
                    .setMessage(R.string.update_permission_message)
                    .setPositiveButton(R.string.update_open_settings, (d, w) -> openInstallSettings())
                    .setNegativeButton(R.string.update_later, null)
                    .show();
            return;
        }
        setStatus(getString(R.string.update_downloading, 0), STATUS_BUSY);
        updater.install();
    }

    private void openInstallSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
                // no settings app reachable; the message explains where to go
            }
        }
    }

    private void updateAddress() {
        String ip = findIpv4Address();
        String version = getString(R.string.version_label, updater.installedVersionName());
        addressView.setText((ip == null ? getString(R.string.no_network)
                : getString(R.string.address_label, ip)) + "  ·  " + version);
    }

    private static String findIpv4Address() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    private String loadReceiverName() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String name = prefs.getString(PREF_NAME, null);
        if (name == null) {
            String deviceName = Settings.Global.getString(getContentResolver(), "device_name");
            name = (deviceName == null || deviceName.trim().isEmpty())
                    ? getString(R.string.default_receiver_name)
                    : deviceName.trim() + " AirPlay";
            // keep it short and free of characters that do not belong in an mDNS label
            name = name.replace('.', ' ').replace('@', ' ');
            if (name.length() > 40) {
                name = name.substring(0, 40).trim();
            }
            prefs.edit().putString(PREF_NAME, name).apply();
        }
        return name;
    }

    /**
     * AirPlay identifies receivers by a MAC address. Android apps cannot read the real
     * one, so a random, locally-administered address is generated once and kept.
     */
    private String loadDeviceId() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String id = prefs.getString(PREF_DEVICE_ID, null);
        if (id == null) {
            byte[] b = new byte[6];
            new SecureRandom().nextBytes(b);
            b[0] = (byte) ((b[0] & 0xFC) | 0x02); // unicast, locally administered
            id = String.format(Locale.US, "%02x:%02x:%02x:%02x:%02x:%02x",
                    b[0], b[1], b[2], b[3], b[4], b[5]);
            prefs.edit().putString(PREF_DEVICE_ID, id).apply();
        }
        return id;
    }
}
