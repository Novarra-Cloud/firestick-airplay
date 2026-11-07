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
    private View osdView;
    private android.widget.ImageView osdState;
    private TextView osdPosition;
    private TextView osdDuration;
    private android.widget.ProgressBar osdProgress;
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
        osdView = findViewById(R.id.osd);
        osdState = findViewById(R.id.osd_state);
        osdPosition = findViewById(R.id.osd_position);
        osdDuration = findViewById(R.id.osd_duration);
        osdProgress = findViewById(R.id.osd_progress);
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
            if (hlsPlayer.isActive()) {
                // screen mirroring started: it replaces a video still playing on the TV
                hlsPlayer.stop(false);
                hlsSurfaceView.setVisibility(View.GONE);
            }
            surfaceView.setVisibility(View.VISIBLE);
            idleScreen.setVisibility(View.GONE);
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

    // ------------------------------------------------------------------ TV remote

    /*
     * Buttons while a video handed over with the AirPlay button is playing:
     *   OK / Play-Pause         pause or resume
     *   Rewind / Fast-forward   back / forward 10 s (hold to keep skipping, faster after 2 s)
     *   D-pad left / right      same as rewind / fast-forward
     *   D-pad up / down         show the progress bar
     *   Menu (☰) or CC          subtitles on/off  (hold Menu: show last subtitle message)
     *   Back / Stop             stop the video
     * Each press acts once; the release is swallowed as well, because Fire OS otherwise
     * passes the release of media buttons on to the system (which undid a pause).
     */

    private static final long SEEK_STEP_MS = 10_000;
    private static final long SEEK_STEP_FAST_MS = 30_000;
    private static final long SEEK_REPEAT_INTERVAL_MS = 250;

    private long seekHoldStartMs;
    private long lastSeekRepeatMs;
    private boolean menuLongPressed;
    private boolean debugShowing;

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean videoOnScreen = idleScreen.getVisibility() != View.VISIBLE;
        boolean airplayVideo = hlsPlayer.isActive() && videoOnScreen;

        if (!airplayVideo) {
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                return handleMenuKey(event, false);
            }
            if (videoOnScreen && isPlaybackKey(keyCode)) {
                // screen mirroring: the TV only shows the phone's screen
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    showToast(getString(R.string.mirroring_remote_hint), 3000);
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean first = down && event.getRepeatCount() == 0;
        switch (keyCode) {
            case KeyEvent.KEYCODE_MENU:
                return handleMenuKey(event, true);
            case KeyEvent.KEYCODE_CAPTIONS:
                if (first) {
                    toggleSubtitles();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                if (first) {
                    hlsPlayer.togglePause();
                    showOsd();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (first) {
                    hlsPlayer.setPausedByRemote(false);
                    showOsd();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (first) {
                    hlsPlayer.setPausedByRemote(true);
                    showOsd();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_STEP_FORWARD:
                if (down) {
                    seekWithRemote(event, +1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD:
            case KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD:
                if (down) {
                    seekWithRemote(event, -1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_INFO:
                if (first) {
                    if (osdView.getVisibility() == View.VISIBLE && !hlsPlayer.isPaused()) {
                        hideOsd();
                    } else {
                        showOsd();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                if (first) {
                    hideOsd();
                    hlsPlayer.stop();
                }
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    private static boolean isPlaybackKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            default:
                return false;
        }
    }

    /** Short press: subtitles on/off. Long press: show the last subtitle message (troubleshooting). */
    private boolean handleMenuKey(KeyEvent event, boolean subtitlesAvailable) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                menuLongPressed = false;
            } else if (!menuLongPressed) {
                menuLongPressed = true;
                showSubtitleDebug();
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP && !menuLongPressed) {
            if (debugShowing && toastView.getVisibility() == View.VISIBLE) {
                debugShowing = false;
                toastView.setVisibility(View.GONE);
            } else if (subtitlesAvailable) {
                toggleSubtitles();
            } else if (toastView.getVisibility() == View.VISIBLE) {
                toastView.setVisibility(View.GONE);
            }
        }
        return true;
    }

    private void seekWithRemote(KeyEvent event, int direction) {
        long now = System.currentTimeMillis();
        if (event.getRepeatCount() == 0) {
            seekHoldStartMs = now;
        } else if (now - lastSeekRepeatMs < SEEK_REPEAT_INTERVAL_MS) {
            return; // auto-repeat while held: limit to 4 steps per second
        }
        lastSeekRepeatMs = now;
        long step = (now - seekHoldStartMs > 2000) ? SEEK_STEP_FAST_MS : SEEK_STEP_MS;
        hlsPlayer.seekBy(direction * step);
        showOsd();
    }

    private void toggleSubtitles() {
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
    }

    /** Hold ☰: shows the recent AirPlay activity (and last subtitle message) for troubleshooting. */
    private void showSubtitleDebug() {
        StringBuilder sb = new StringBuilder();
        String log = server.activityLog();
        sb.append(getString(R.string.debug_activity_title)).append('\n');
        sb.append(log.isEmpty() ? getString(R.string.debug_activity_empty) : log);
        String sub = server.lastSubtitleMessage();
        if (sub != null) {
            sb.append("\n\n").append(getString(R.string.debug_subtitle_message)).append(' ')
                    .append(sub.split("\n", 2)[0]);
        }
        toastView.setTextSize(13);
        showToast(sb.toString(), 120_000);
        debugShowing = true;
    }

    // ------------------------------------------------------------------ progress bar (OSD)

    private static final long OSD_TIMEOUT_MS = 4000;
    private long osdHideAtMs;

    private final Runnable osdUpdater = new Runnable() {
        @Override
        public void run() {
            if (osdView.getVisibility() != View.VISIBLE) {
                return;
            }
            if (!hlsPlayer.isActive()) {
                hideOsd();
                return;
            }
            boolean paused = hlsPlayer.isPaused();
            if (!paused && System.currentTimeMillis() > osdHideAtMs) {
                hideOsd();
                return;
            }
            long pos = hlsPlayer.positionMs();
            long dur = hlsPlayer.durationMs();
            osdState.setImageResource(paused ? R.drawable.ic_pause : R.drawable.ic_play);
            osdPosition.setText(formatTime(pos));
            osdDuration.setText(dur > 0 ? formatTime(dur) : "");
            osdProgress.setProgress(dur > 0 ? (int) (pos * 1000 / dur) : 0);
            osdView.postDelayed(this, 250);
        }
    };

    /** Shows the progress bar; it stays while paused and hides a few seconds after playing. */
    private void showOsd() {
        osdHideAtMs = System.currentTimeMillis() + OSD_TIMEOUT_MS;
        osdView.removeCallbacks(osdUpdater);
        osdView.setVisibility(View.VISIBLE);
        osdUpdater.run();
    }

    private void hideOsd() {
        osdView.removeCallbacks(osdUpdater);
        osdView.setVisibility(View.GONE);
    }

    private static String formatTime(long ms) {
        long total = ms / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long sec = total % 60;
        return (h > 0) ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.US, "%d:%02d", m, sec);
    }

    /** Shortens plist XML so it fits on screen: drops the XML header and tags around values. */
    private static String compactXml(String xml) {
        String s = xml.replaceAll("(?s)<\\?xml.*?\\?>", "")
                .replaceAll("(?s)<!DOCTYPE.*?>", "")
                .replaceAll("</?plist[^>]*>", "")
                .replaceAll("<key>([^<]*)</key>\\s*", "$1 = ")
                .replaceAll("<string>([^<]*)</string>", "$1")
                .replaceAll("<(true|false)/>", "$1")
                .replaceAll("<integer>([^<]*)</integer>", "$1")
                .replaceAll("MediaSelection", "")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("(?m)^\\s+", "")
                .replaceAll("\\n{2,}", "\n")
                .trim();
        return s.length() > 900 ? s.substring(0, 900) + "…" : s;
    }

    private void showToast(String text, long millis) {
        if (millis < 60_000) {
            toastView.setTextSize(18);
            debugShowing = false;
        }
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
        // remember the choice for the next videos (and next time the app opens)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_SUBTITLES, enabled).apply();
        server.addActivity("TV: subtitles " + (enabled ? "on" : "off") + " (from iPhone)");
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
    public void onAirPlaySubtitleOffIgnored() {
        server.addActivity("TV: kept subtitles on (ignored automatic off at video start)");
    }

    @Override
    public void onAirPlayVideoDetached() {
        server.addActivity("TV: phone let go, still playing");
        showToast(getString(R.string.video_detached), 6000);
    }

    @Override
    public void onAirPlayVideoStopped() {
        server.addActivity("TV: video stopped, back to start screen");
        hideOsd();
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
