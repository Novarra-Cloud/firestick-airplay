package com.novarra.firestickairplay;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * Decodes the AirPlay audio stream with MediaCodec and plays it through an AudioTrack.
 *
 * AirPlay compression types ("ct"): 2 = ALAC (audio-only streaming), 4 = AAC-LC,
 * 8 = AAC-ELD (used while screen mirroring). All are 44.1 kHz stereo.
 */
public final class AudioPlayer {

    private static final String TAG = "AudioPlayer";
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNELS = 2;

    /** AudioSpecificConfig for AAC-ELD 44.1 kHz stereo, 480 samples per frame. */
    private static final byte[] CSD_AAC_ELD = {(byte) 0xF8, (byte) 0xE8, 0x50, 0x00};
    /** AudioSpecificConfig for AAC-LC 44.1 kHz stereo. */
    private static final byte[] CSD_AAC_LC = {0x12, 0x10};
    /** ALACSpecificConfig (352 frames per packet, 16 bit, 2 channels, 44100 Hz). */
    private static final byte[] CSD_ALAC = {
            0x00, 0x00, 0x01, 0x60, 0x00, 0x10, 0x28, 0x0a, 0x0e, 0x02, 0x00, (byte) 0xff,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, (byte) 0xac, 0x44};

    private final Object lock = new Object();
    private MediaCodec codec;
    private AudioTrack track;
    private Thread outputThread;
    private volatile boolean outputRunning;
    private int compressionType = -1;
    private volatile float volume = 1.0f;

    /** Prepares a decoder for the given compression type (no-op if already set up). */
    public void configure(int ct) {
        synchronized (lock) {
            if (ct == compressionType && codec != null) {
                return;
            }
            releaseLocked();
            compressionType = ct;
            createLocked(ct);
        }
    }

    public void onFrame(byte[] data, int ct) {
        synchronized (lock) {
            if (ct != compressionType) {
                releaseLocked();
                compressionType = ct;
                createLocked(ct);
            }
            if (codec == null) {
                return;
            }
            try {
                int index = codec.dequeueInputBuffer(10_000);
                if (index < 0) {
                    return; // decoder is behind; drop this packet
                }
                ByteBuffer buffer = codec.getInputBuffer(index);
                if (buffer == null || buffer.capacity() < data.length) {
                    codec.queueInputBuffer(index, 0, 0, 0, 0);
                    return;
                }
                buffer.clear();
                buffer.put(data);
                codec.queueInputBuffer(index, 0, data.length, System.nanoTime() / 1000, 0);
            } catch (IllegalStateException e) {
                Log.e(TAG, "audio decoder error; restarting", e);
                releaseLocked();
                compressionType = -1;
            }
        }
    }

    /** Sets the playback gain (0.0 = silent, 1.0 = full). */
    public void setVolume(float gain) {
        volume = gain;
        synchronized (lock) {
            if (track != null) {
                track.setVolume(gain);
            }
        }
    }

    public void release() {
        synchronized (lock) {
            releaseLocked();
            compressionType = -1;
        }
    }

    private void createLocked(int ct) {
        String mime;
        byte[] csd;
        switch (ct) {
            case 8:
                mime = MediaFormat.MIMETYPE_AUDIO_AAC;
                csd = CSD_AAC_ELD;
                break;
            case 4:
                mime = MediaFormat.MIMETYPE_AUDIO_AAC;
                csd = CSD_AAC_LC;
                break;
            case 2:
                mime = "audio/alac";
                csd = CSD_ALAC;
                break;
            default:
                Log.w(TAG, "unsupported audio compression type " + ct);
                return;
        }
        try {
            MediaFormat format = MediaFormat.createAudioFormat(mime, SAMPLE_RATE, CHANNELS);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd));
            if (ct == 8) {
                format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectELD);
                format.setInteger(MediaFormat.KEY_IS_ADTS, 0);
            } else if (ct == 4) {
                format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                format.setInteger(MediaFormat.KEY_IS_ADTS, 0);
            }
            MediaCodec c = MediaCodec.createDecoderByType(mime);
            c.configure(format, null, null, 0);
            c.start();

            int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build())
                    .setBufferSizeInBytes(Math.max(minBuf * 2, 16384))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            t.setVolume(volume);
            t.play();

            codec = c;
            track = t;
            outputRunning = true;
            outputThread = new Thread(() -> drain(c, t), "AudioDecoderOutput");
            outputThread.start();
            Log.i(TAG, "audio decoder started for ct=" + ct + ": " + c.getName());
        } catch (Exception e) {
            Log.e(TAG, "could not start audio decoder for ct=" + ct, e);
            codec = null;
            track = null;
        }
    }

    private void drain(MediaCodec c, AudioTrack t) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] pcm = new byte[0];
        while (outputRunning) {
            int index;
            try {
                index = c.dequeueOutputBuffer(info, 20_000);
            } catch (IllegalStateException e) {
                break;
            }
            if (index < 0) {
                continue;
            }
            try {
                ByteBuffer out = c.getOutputBuffer(index);
                if (out != null && info.size > 0) {
                    if (pcm.length < info.size) {
                        pcm = new byte[info.size];
                    }
                    out.position(info.offset);
                    out.get(pcm, 0, info.size);
                    c.releaseOutputBuffer(index, false);
                    t.write(pcm, 0, info.size);
                } else {
                    c.releaseOutputBuffer(index, false);
                }
            } catch (IllegalStateException e) {
                break;
            }
        }
    }

    private void releaseLocked() {
        MediaCodec c = codec;
        AudioTrack t = track;
        codec = null;
        track = null;
        outputRunning = false;
        Thread th = outputThread;
        outputThread = null;
        if (t != null) {
            try {
                t.pause();
                t.flush();
            } catch (IllegalStateException ignored) {
                // not playing
            }
        }
        if (th != null) {
            try {
                th.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (c != null) {
            try {
                c.stop();
            } catch (Exception ignored) {
                // already stopped
            }
            c.release();
        }
        if (t != null) {
            t.release();
        }
    }
}
