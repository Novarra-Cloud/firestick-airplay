package com.novarra.firestickairplay;

import android.util.Log;

/**
 * Java side of the native AirPlay receiver (app/src/main/cpp/airplay_jni.c).
 *
 * The native code advertises this device over mDNS as an AirPlay receiver, handles the
 * pairing handshake with the iPhone/iPad/Mac and delivers decrypted H.264 video and AAC
 * audio frames to the callbacks below. Callbacks arrive on native threads.
 */
public final class AirPlayServer {

    private static final String TAG = "AirPlayServer";

    static {
        System.loadLibrary("airplayjni");
    }

    /** UI-facing events. Called on native threads; implementations must post to the UI thread. */
    public interface Listener {
        void onClientConnecting(String name, String model);

        void onConnectionCount(int count);

        void onMirroringStopped();

        void onStatus(String message);
    }

    private final VideoDecoder video;
    private final AudioPlayer audio;
    private volatile Listener listener;
    private volatile boolean running;

    public AirPlayServer(VideoDecoder video, AudioPlayer audio) {
        this.video = video;
        this.audio = audio;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Starts advertising and accepting connections.
     *
     * @param name     name shown in the iPhone's Screen Mirroring list
     * @param deviceId a stable MAC-style id (aa:bb:cc:dd:ee:ff) identifying this receiver
     * @param keyFile  file used to persist the receiver's pairing key
     * @return 0 on success, a negative error code otherwise
     */
    public synchronized int start(String name, String deviceId, String keyFile,
                                  int width, int height, int fps) {
        if (running) {
            return 0;
        }
        int result = nativeStart(name, deviceId, keyFile, width, height, fps);
        running = (result == 0);
        return result;
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        nativeStop();
        video.stop();
        audio.release();
    }

    public boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------------ native

    private native int nativeStart(String name, String deviceId, String keyFile,
                                   int width, int height, int fps);

    private native void nativeStop();

    // ------------------------------------------------------------------ callbacks from native code

    @SuppressWarnings("unused")
    private void onVideoFrame(byte[] data, long ntpTime) {
        video.onFrame(data);
    }

    @SuppressWarnings("unused")
    private void onVideoSize(int sourceWidth, int sourceHeight, int width, int height) {
        Log.i(TAG, "video size source " + sourceWidth + "x" + sourceHeight
                + " display " + width + "x" + height);
    }

    @SuppressWarnings("unused")
    private void onVideoStop() {
        video.stop();
        audio.release();
        Listener l = listener;
        if (l != null) {
            l.onMirroringStopped();
        }
    }

    @SuppressWarnings("unused")
    private void onAudioFormat(int compressionType) {
        audio.configure(compressionType);
    }

    @SuppressWarnings("unused")
    private void onAudioFrame(byte[] data, int compressionType) {
        audio.onFrame(data, compressionType);
    }

    @SuppressWarnings("unused")
    private void onVolume(float gain) {
        audio.setVolume(gain);
    }

    @SuppressWarnings("unused")
    private void onClientConnecting(String name, String model) {
        Listener l = listener;
        if (l != null) {
            l.onClientConnecting(name, model);
        }
    }

    @SuppressWarnings("unused")
    private void onConnectionCount(int count) {
        if (count == 0) {
            video.stop();
            audio.release();
        }
        Listener l = listener;
        if (l != null) {
            l.onConnectionCount(count);
        }
    }

    @SuppressWarnings("unused")
    private void onNativeLog(int level, String message) {
        Listener l = listener;
        if (l != null && message != null && level <= 5 /* LOGGER_NOTICE */) {
            l.onStatus(message.trim());
        }
    }
}
