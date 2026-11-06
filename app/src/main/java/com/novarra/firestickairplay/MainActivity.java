package com.novarra.firestickairplay;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

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
        VideoDecoder.SizeListener, SurfaceHolder.Callback, HlsPlayer.Listener {

    private static final String PREFS = "airplay";
    private static final String PREF_DEVICE_ID = "device_id";
    private static final String PREF_NAME = "receiver_name";

    private final VideoDecoder videoDecoder = new VideoDecoder();
    private final AudioPlayer audioPlayer = new AudioPlayer();
    private AirPlayServer server;
    private HlsPlayer hlsPlayer;

    private SurfaceView surfaceView;
    private SurfaceView hlsSurfaceView;
    private View idleScreen;
    private TextView nameView;
    private TextView statusView;
    private TextView addressView;

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
        addressView = findViewById(R.id.address);

        surfaceView.getHolder().addCallback(this);
        videoDecoder.setSizeListener(this);
        server = new AirPlayServer(videoDecoder, audioPlayer);
        server.setListener(this);
        hlsPlayer = new HlsPlayer(this, hlsSurfaceView, this);
        server.setHlsPlayer(hlsPlayer);

        receiverName = loadReceiverName();
        nameView.setText(getString(R.string.receiver_name_label, receiverName));
    }

    @Override
    protected void onStart() {
        super.onStart();
        hideSystemUi();
        acquireNetworkLocks();
        startServer();
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
            statusView.setText(R.string.status_ready);
        } else {
            statusView.setText(getString(R.string.status_failed, result));
        }
    }

    // ------------------------------------------------------------------ AirPlayServer.Listener

    @Override
    public void onClientConnecting(String name, String model) {
        runOnUiThread(() -> statusView.setText(getString(R.string.status_connecting, name)));
    }

    @Override
    public void onConnectionCount(int count) {
        if (count == 0 && !hlsPlayer.isActive()) {
            runOnUiThread(() -> {
                showIdle();
                statusView.setText(R.string.status_ready);
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
            statusView.setText(R.string.status_ready);
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
        statusView.setText(R.string.status_loading_video);
        hlsSurfaceView.setVisibility(View.VISIBLE);
    }

    @Override
    public void onAirPlayVideoFirstFrame() {
        idleScreen.setVisibility(View.GONE);
    }

    @Override
    public void onAirPlayVideoSizeChanged(int width, int height) {
        hlsWidth = width;
        hlsHeight = height;
        fitSurface(hlsSurfaceView, hlsWidth, hlsHeight);
    }

    @Override
    public void onAirPlayVideoStopped() {
        hlsSurfaceView.setVisibility(View.GONE);
        showIdle();
        statusView.setText(R.string.status_ready);
    }

    @Override
    public void onAirPlayVideoError(String message) {
        hlsSurfaceView.setVisibility(View.GONE);
        showIdle();
        statusView.setText(getString(R.string.status_video_error, message));
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

    private void updateAddress() {
        String ip = findIpv4Address();
        addressView.setText(ip == null ? getString(R.string.no_network)
                : getString(R.string.address_label, ip));
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
