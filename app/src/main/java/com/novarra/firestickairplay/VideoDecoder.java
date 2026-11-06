package com.novarra.firestickairplay;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Hardware H.264 decoder for the AirPlay mirroring stream.
 *
 * Frames arrive as Annex-B access units (4-byte start codes). The SPS/PPS NAL units are
 * prepended to the first frame and whenever the stream changes (e.g. the phone is
 * rotated); a change in SPS re-creates the decoder. Decoded frames are rendered straight
 * to the output Surface as soon as they are available, which keeps latency low.
 */
public final class VideoDecoder {

    private static final String TAG = "VideoDecoder";
    private static final String MIME = "video/avc";

    /** Receives the size of the decoded picture so the view can keep the aspect ratio. */
    public interface SizeListener {
        void onVideoSizeChanged(int width, int height);

        void onFirstFrame();
    }

    private final Object lock = new Object();
    private Surface surface;
    private MediaCodec codec;
    private Thread outputThread;
    private volatile boolean outputRunning;
    private byte[] sps;
    private byte[] pps;
    private volatile SizeListener sizeListener;
    private boolean waitingForKeyFrame = true;
    private int droppedFrames;

    public void setSizeListener(SizeListener listener) {
        this.sizeListener = listener;
    }

    /** Sets (or clears, with null) the Surface decoded video is drawn to. */
    public void setSurface(Surface newSurface) {
        synchronized (lock) {
            if (surface == newSurface) {
                return;
            }
            releaseCodecLocked();
            surface = newSurface;
            if (surface != null && sps != null && pps != null) {
                createCodecLocked();
            }
        }
    }

    /** Stops decoding (the client stopped mirroring). The cached SPS/PPS is discarded. */
    public void stop() {
        synchronized (lock) {
            releaseCodecLocked();
            sps = null;
            pps = null;
        }
    }

    /** Queues one Annex-B access unit for decoding. */
    public void onFrame(byte[] data) {
        synchronized (lock) {
            boolean hasKeyFrame = scanParameterSets(data);
            if (codec == null) {
                if (surface == null || sps == null || pps == null) {
                    return;
                }
                createCodecLocked();
                if (codec == null) {
                    return;
                }
            }
            if (waitingForKeyFrame) {
                if (!hasKeyFrame) {
                    return;
                }
                waitingForKeyFrame = false;
            }
            try {
                int index = codec.dequeueInputBuffer(30_000);
                if (index < 0) {
                    if (++droppedFrames % 30 == 1) {
                        Log.w(TAG, "decoder busy, dropped " + droppedFrames + " frames");
                    }
                    return;
                }
                ByteBuffer buffer = codec.getInputBuffer(index);
                if (buffer == null || buffer.capacity() < data.length) {
                    Log.w(TAG, "frame of " + data.length + " bytes does not fit input buffer");
                    codec.queueInputBuffer(index, 0, 0, 0, 0);
                    return;
                }
                buffer.clear();
                buffer.put(data);
                codec.queueInputBuffer(index, 0, data.length, System.nanoTime() / 1000, 0);
            } catch (IllegalStateException e) {
                Log.e(TAG, "decoder error; restarting", e);
                releaseCodecLocked();
            }
        }
    }

    /**
     * Extracts SPS (type 7) and PPS (type 8) NAL units from an access unit. If the SPS
     * changed, the current decoder is released so a new one is created with the new
     * parameters. Returns true if the access unit contains an IDR slice (type 5).
     */
    private boolean scanParameterSets(byte[] data) {
        boolean idr = false;
        byte[] newSps = null;
        byte[] newPps = null;
        int start = nextStartCode(data, 0);
        while (start >= 0) {
            int nalStart = start + startCodeLength(data, start);
            int next = nextStartCode(data, nalStart);
            int nalEnd = (next < 0) ? data.length : next;
            if (nalStart < nalEnd) {
                int type = data[nalStart] & 0x1f;
                if (type == 7) {
                    newSps = Arrays.copyOfRange(data, nalStart, nalEnd);
                } else if (type == 8) {
                    newPps = Arrays.copyOfRange(data, nalStart, nalEnd);
                } else if (type == 5) {
                    idr = true;
                    break; // slice data follows; no need to scan it
                } else if (type == 1) {
                    break;
                }
            }
            start = next;
        }
        if (newSps != null && newPps != null) {
            boolean changed = !Arrays.equals(newSps, sps) || !Arrays.equals(newPps, pps);
            sps = newSps;
            pps = newPps;
            if (changed && codec != null) {
                Log.i(TAG, "stream parameters changed; recreating decoder");
                releaseCodecLocked();
            }
        }
        return idr;
    }

    private static int nextStartCode(byte[] d, int from) {
        for (int i = from; i + 3 < d.length; i++) {
            if (d[i] == 0 && d[i + 1] == 0) {
                if (d[i + 2] == 1) {
                    return i;
                }
                if (d[i + 2] == 0 && d[i + 3] == 1) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int startCodeLength(byte[] d, int pos) {
        return (d[pos + 2] == 1) ? 3 : 4;
    }

    private static ByteBuffer withStartCode(byte[] nal) {
        ByteBuffer b = ByteBuffer.allocate(nal.length + 4);
        b.put(new byte[] {0, 0, 0, 1}).put(nal).flip();
        return b;
    }

    private void createCodecLocked() {
        try {
            // The real size comes from the SPS; this is only a hint for buffer allocation.
            MediaFormat format = MediaFormat.createVideoFormat(MIME, 1920, 1080);
            format.setByteBuffer("csd-0", withStartCode(sps));
            format.setByteBuffer("csd-1", withStartCode(pps));
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024);
            if (Build.VERSION.SDK_INT >= 30) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            }
            MediaCodec c = MediaCodec.createDecoderByType(MIME);
            c.configure(format, surface, null, 0);
            c.start();
            codec = c;
            waitingForKeyFrame = true;
            droppedFrames = 0;
            startOutputThread(c);
            Log.i(TAG, "decoder started: " + c.getName());
        } catch (Exception e) {
            Log.e(TAG, "could not create H.264 decoder", e);
            codec = null;
        }
    }

    private void startOutputThread(final MediaCodec c) {
        outputRunning = true;
        outputThread = new Thread(() -> drainOutput(c), "VideoDecoderOutput");
        outputThread.start();
    }

    private void drainOutput(MediaCodec c) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean first = true;
        while (outputRunning) {
            int index;
            try {
                index = c.dequeueOutputBuffer(info, 20_000);
            } catch (IllegalStateException e) {
                break;
            }
            if (index >= 0) {
                try {
                    c.releaseOutputBuffer(index, true);
                } catch (IllegalStateException e) {
                    break;
                }
                if (first) {
                    first = false;
                    SizeListener l = sizeListener;
                    if (l != null) {
                        l.onFirstFrame();
                    }
                }
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                try {
                    reportSize(c.getOutputFormat());
                } catch (IllegalStateException e) {
                    break;
                }
            }
        }
    }

    private void reportSize(MediaFormat f) {
        int width = f.getInteger(MediaFormat.KEY_WIDTH);
        int height = f.getInteger(MediaFormat.KEY_HEIGHT);
        if (f.containsKey("crop-right") && f.containsKey("crop-left")
                && f.containsKey("crop-bottom") && f.containsKey("crop-top")) {
            width = f.getInteger("crop-right") - f.getInteger("crop-left") + 1;
            height = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1;
        }
        Log.i(TAG, "decoded video size " + width + "x" + height);
        SizeListener l = sizeListener;
        if (l != null && width > 0 && height > 0) {
            l.onVideoSizeChanged(width, height);
        }
    }

    private void releaseCodecLocked() {
        MediaCodec c = codec;
        codec = null;
        waitingForKeyFrame = true;
        if (c == null) {
            return;
        }
        outputRunning = false;
        Thread t = outputThread;
        outputThread = null;
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            c.stop();
        } catch (Exception ignored) {
            // already stopped or in error state
        }
        c.release();
    }
}
