package com.novarra.firestickairplay;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceView;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;

/**
 * Plays "AirPlay video": when the AirPlay button inside a video app is used, the iPhone
 * sends the video's address instead of mirroring its screen. The TV then streams the
 * video by itself, so the phone can be locked or used for other things.
 *
 * All ExoPlayer calls happen on the main thread. The AirPlay server calls the public
 * methods from native threads; they post to the main thread. Playback state is cached in
 * volatile fields so the server can answer the iPhone's progress polls without blocking.
 */
public final class HlsPlayer {

    private static final String TAG = "HlsPlayer";

    public interface Listener {
        void onAirPlayVideoStarted();

        void onAirPlayVideoFirstFrame();

        void onAirPlayVideoSizeChanged(int width, int height);

        void onAirPlayVideoStopped();

        void onAirPlayVideoError(String message);
    }

    private final Context context;
    private final SurfaceView surfaceView;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExoPlayer player;

    private volatile boolean active;
    private volatile boolean ended;
    private volatile boolean buffering;
    private volatile double durationSec;
    private volatile double positionSec;
    private volatile double rate;

    private final Runnable updater = new Runnable() {
        @Override
        public void run() {
            updateState();
            if (player != null) {
                main.postDelayed(this, 500);
            }
        }
    };

    public HlsPlayer(Context context, SurfaceView surfaceView, Listener listener) {
        this.context = context.getApplicationContext();
        this.surfaceView = surfaceView;
        this.listener = listener;
    }

    public boolean isActive() {
        return active;
    }

    public void play(String url, float startSeconds) {
        active = true;
        ended = false;
        buffering = true;
        positionSec = startSeconds;
        durationSec = 0;
        rate = 1.0;
        main.post(() -> {
            releasePlayer();
            ExoPlayer p = new ExoPlayer.Builder(context).build();
            player = p;
            p.setVideoSurfaceView(surfaceView);
            p.addListener(playerListener);
            MediaItem.Builder item = new MediaItem.Builder().setUri(Uri.parse(url));
            if (url.contains(".m3u8")) {
                item.setMimeType(MimeTypes.APPLICATION_M3U8);
            }
            p.setMediaItem(item.build(), (long) (Math.max(0f, startSeconds) * 1000));
            p.setPlayWhenReady(true);
            p.prepare();
            listener.onAirPlayVideoStarted();
            main.removeCallbacks(updater);
            main.post(updater);
            Log.i(TAG, "playing " + url + " from " + startSeconds + " s");
        });
    }

    public void seek(float seconds) {
        positionSec = seconds;
        main.post(() -> {
            if (player != null) {
                player.seekTo((long) (Math.max(0f, seconds) * 1000));
            }
        });
    }

    /** rate 0 = pause, 1 = play (as sent by the iPhone's play/pause button). */
    public void setRate(float newRate) {
        rate = newRate;
        main.post(() -> {
            if (player != null) {
                player.setPlayWhenReady(newRate > 0f);
            }
        });
    }

    /** Pauses and returns the current position (used when the iPhone swaps playlists). */
    public float pauseAndGetPosition() {
        main.post(() -> {
            if (player != null) {
                player.pause();
            }
        });
        return (float) positionSec;
    }

    public void stop() {
        boolean wasActive = active;
        active = false;
        main.post(() -> {
            releasePlayer();
            if (wasActive) {
                listener.onAirPlayVideoStopped();
            }
        });
    }

    /** {duration, position, rate, stillPlaying (1/0), buffering (1/0)} for the iPhone. */
    public double[] playbackInfo() {
        boolean playing = active && !ended;
        return new double[] {durationSec, positionSec, rate, playing ? 1 : 0, buffering ? 1 : 0};
    }

    private void updateState() {
        ExoPlayer p = player;
        if (p == null) {
            return;
        }
        long dur = p.getDuration();
        durationSec = (dur == C.TIME_UNSET) ? 0 : dur / 1000.0;
        positionSec = Math.max(0, p.getCurrentPosition()) / 1000.0;
        rate = p.getPlayWhenReady() ? 1.0 : 0.0;
        buffering = p.getPlaybackState() == Player.STATE_BUFFERING;
    }

    private void releasePlayer() {
        main.removeCallbacks(updater);
        if (player != null) {
            player.removeListener(playerListener);
            player.release();
            player = null;
        }
    }

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            updateState();
            if (state == Player.STATE_ENDED) {
                Log.i(TAG, "video ended");
                ended = true;
            }
        }

        @Override
        public void onRenderedFirstFrame() {
            listener.onAirPlayVideoFirstFrame();
        }

        @Override
        public void onVideoSizeChanged(VideoSize size) {
            if (size.width > 0 && size.height > 0) {
                int w = (int) (size.width * (size.pixelWidthHeightRatio > 0 ? size.pixelWidthHeightRatio : 1f));
                listener.onAirPlayVideoSizeChanged(w, size.height);
            }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            Log.e(TAG, "playback error", error);
            ended = true;
            listener.onAirPlayVideoError(error.getErrorCodeName());
        }
    };
}
