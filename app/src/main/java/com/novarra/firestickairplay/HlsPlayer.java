package com.novarra.firestickairplay;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceView;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.CueGroup;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.SubtitleView;

import java.util.Collections;
import java.util.Locale;

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

        /** Subtitles were switched on the iPhone. language is null when off, "" if unknown,
         *  and hasTracks is false when the video stream carries no subtitles at all. */
        void onAirPlaySubtitlesChanged(boolean enabled, String language, boolean hasTracks);

        /** The iPhone stopped controlling the video, but the TV keeps playing it. */
        void onAirPlayVideoDetached();
    }

    private final Context context;
    private final SurfaceView surfaceView;
    private final SubtitleView subtitleView;
    private final Listener listener;
    private boolean subtitlesEnabled;
    private String phoneLanguage; // language picked on the iPhone, e.g. "es"
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExoPlayer player;

    private volatile boolean active;
    /** true when the phone let go but the TV kept playing (stop with the remote) */
    private volatile boolean detached;
    private volatile long lastRemoteControlMs;
    private String currentUrl;
    private volatile boolean ended;
    /** when the video reached its natural end (0 = not ended, or ended by an error) */
    private volatile long endedAtMs;
    /**
     * After a natural end the iPhone is told "paused at the end" for this long, which is
     * what its own player reports when a video finishes; many video apps use
     * that to start the next video (autoplay). Only then is "finished" (-1) reported.
     */
    private static final long END_GRACE_MS = 15_000;
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

    public HlsPlayer(Context context, SurfaceView surfaceView, SubtitleView subtitleView,
                     boolean subtitlesEnabled, Listener listener) {
        this.context = context.getApplicationContext();
        this.surfaceView = surfaceView;
        this.subtitleView = subtitleView;
        this.subtitlesEnabled = subtitlesEnabled;
        this.listener = listener;
        subtitleView.setUserDefaultStyle();
        subtitleView.setUserDefaultTextSize();
    }

    public boolean isActive() {
        return active;
    }

    public void play(String url, float startSeconds) {
        active = true;
        detached = false;
        ended = false;
        endedAtMs = 0;
        buffering = true;
        positionSec = startSeconds;
        durationSec = 0;
        rate = 1.0;
        main.post(() -> {
            releasePlayer();
            currentUrl = url;
            ExoPlayer p = new ExoPlayer.Builder(context).build();
            player = p;
            p.setVideoSurfaceView(surfaceView);
            p.addListener(playerListener);
            applySubtitleSelection(p);
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
        if (System.currentTimeMillis() - lastRemoteControlMs < 3000) {
            // the phone is catching up with a pause/play just done on the TV remote
            return;
        }
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

    // ------------------------------------------------------------------ TV remote controls (main thread)

    public boolean subtitlesEnabled() {
        return subtitlesEnabled;
    }

    /**
     * Turns subtitles on/off. Returns the language now shown, "" when turned off, or null
     * when the current video has no subtitles.
     */
    public String toggleSubtitles() {
        subtitlesEnabled = !subtitlesEnabled;
        ExoPlayer p = player;
        if (p != null) {
            applySubtitleSelection(p);
            if (!p.getCurrentTracks().containsType(C.TRACK_TYPE_TEXT)) {
                return subtitlesEnabled ? null : "";
            }
        }
        if (!subtitlesEnabled) {
            return "";
        }
        String lang = subtitleLanguage();
        return lang.isEmpty() ? "?" : lang;
    }

    public void togglePause() {
        ExoPlayer p = player;
        if (p != null) {
            setPausedByRemote(p.getPlayWhenReady());
        }
    }

    /** Current position in ms (main thread). */
    public long positionMs() {
        ExoPlayer p = player;
        return p == null ? 0 : Math.max(0, p.getCurrentPosition());
    }

    /** Duration in ms, or 0 if unknown (main thread). */
    public long durationMs() {
        ExoPlayer p = player;
        long d = (p == null) ? C.TIME_UNSET : p.getDuration();
        return d == C.TIME_UNSET ? 0 : d;
    }

    /** True when paused (main thread). */
    public boolean isPaused() {
        ExoPlayer p = player;
        return p != null && !p.getPlayWhenReady();
    }

    public void setPausedByRemote(boolean paused) {
        ExoPlayer p = player;
        if (p != null) {
            lastRemoteControlMs = System.currentTimeMillis();
            p.setPlayWhenReady(!paused);
            updateState();
        }
    }

    public void seekBy(long millis) {
        ExoPlayer p = player;
        if (p != null) {
            long target = Math.max(0, p.getCurrentPosition() + millis);
            long dur = p.getDuration();
            if (dur != C.TIME_UNSET) {
                target = Math.min(target, Math.max(0, dur - 1000));
            }
            p.seekTo(target);
            updateState();
        }
    }

    /** Follows the subtitle choice made in the iPhone's video player. */
    public void setSubtitlesFromPhone(boolean enabled, String language) {
        main.post(() -> {
            subtitlesEnabled = enabled;
            if (language != null && !language.isEmpty()) {
                phoneLanguage = language.split("[-_]")[0];
            }
            ExoPlayer p = player;
            boolean hasTracks = true;
            if (p != null) {
                applySubtitleSelection(p);
                hasTracks = p.getPlaybackState() != Player.STATE_READY
                        || p.getCurrentTracks().containsType(C.TRACK_TYPE_TEXT);
            }
            listener.onAirPlaySubtitlesChanged(enabled,
                    enabled ? subtitleLanguage() : null, hasTracks);
        });
    }

    private void applySubtitleSelection(ExoPlayer p) {
        String[] langs = (phoneLanguage == null)
                ? new String[] {"es", "en"} : new String[] {phoneLanguage, "es", "en"};
        TrackSelectionParameters params = p.getTrackSelectionParameters().buildUpon()
                .setPreferredTextLanguages(langs)
                .setSelectUndeterminedTextLanguage(true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled)
                .build();
        p.setTrackSelectionParameters(params);
        if (!subtitlesEnabled) {
            subtitleView.setCues(Collections.emptyList());
        }
        subtitleView.setVisibility(subtitlesEnabled ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private String subtitleLanguage() {
        ExoPlayer p = player;
        if (p == null) {
            return "";
        }
        String any = null;
        for (Tracks.Group group : p.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) {
                continue;
            }
            for (int i = 0; i < group.length; i++) {
                Format f = group.getTrackFormat(i);
                String name = (f.language == null) ? "" : new Locale(f.language).getDisplayLanguage();
                if (group.isTrackSelected(i)) {
                    return name;
                }
                if (any == null) {
                    any = name;
                }
            }
        }
        return any == null ? "" : any;
    }

    public boolean isDetached() {
        return detached;
    }

    /**
     * The iPhone asked to stop (e.g. the user left the video page in the app). Videos
     * streamed straight from the internet keep playing on the TV; they are stopped with
     * the remote's Back button instead. Videos relayed through the phone (localhost
     * addresses, e.g. YouTube) cannot continue without it and are stopped.
     */
    public void stopFromPhone() {
        main.post(() -> {
            ExoPlayer p = player;
            boolean canContinue = p != null && active && !ended && currentUrl != null
                    && !currentUrl.contains("://localhost") && !currentUrl.contains("://127.0.0.1")
                    && p.getPlaybackState() != Player.STATE_IDLE;
            if (canContinue) {
                if (!detached) {
                    detached = true;
                    Log.i(TAG, "phone stopped controlling; continuing playback on the TV");
                    listener.onAirPlayVideoDetached();
                }
                return;
            }
            stop();
        });
    }

    /** Stops playback and shows the start screen. */
    public void stop() {
        stop(true);
    }

    /** Stops playback; notify=false when something else (screen mirroring) takes over. */
    public void stop(boolean notify) {
        boolean wasActive = active;
        active = false;
        detached = false;
        main.post(() -> {
            releasePlayer();
            if (wasActive && notify) {
                listener.onAirPlayVideoStopped();
            }
        });
    }

    /** {duration, position, rate, stillPlaying (1/0), buffering (1/0)} for the iPhone. */
    public double[] playbackInfo() {
        if (active && ended && endedAtMs != 0
                && System.currentTimeMillis() - endedAtMs < END_GRACE_MS) {
            // reached the end normally: report "at the end, paused", still loaded
            return new double[] {durationSec, durationSec, 0, 1, 0};
        }
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
        subtitleView.setCues(Collections.emptyList());
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
            if ((state == Player.STATE_READY || state == Player.STATE_BUFFERING) && ended && endedAtMs != 0) {
                // playback resumed after the end (e.g. skipped back with the remote)
                ended = false;
                endedAtMs = 0;
            }
            if (state == Player.STATE_ENDED) {
                Log.i(TAG, "video ended");
                if (durationSec > 0) {
                    positionSec = durationSec;
                }
                endedAtMs = System.currentTimeMillis();
                ended = true;
                if (detached) {
                    // nobody on the phone will send the next video: go back to the start screen
                    stop();
                }
            }
        }

        @Override
        public void onCues(CueGroup cueGroup) {
            subtitleView.setCues(cueGroup.cues);
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
