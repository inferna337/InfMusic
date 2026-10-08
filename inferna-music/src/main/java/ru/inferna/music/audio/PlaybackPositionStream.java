package ru.inferna.music.audio;

import net.minecraft.client.sound.AudioStream;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Tracks audio queued by Minecraft separately from audio already played by OpenAL.
 */
public final class PlaybackPositionStream implements AudioStream {
    private final AudioStream delegate;
    private final Queue<Integer> queuedBufferBytes = new ArrayDeque<>();
    private long submittedBytes;
    private long playedBytes;
    private int sampleOffset;
    private boolean ended;
    private boolean closed;
    private long baseBytes;
    private long pendingSkipBytes;

    /** Requests a forward seek; decoded bytes are discarded as they become available. */
    public synchronized void seekSeconds(double seconds) {
        pendingSkipBytes = Math.max(0L, Math.round(seconds * getFormat().getSampleRate() * getFormat().getFrameSize()));
        queuedBufferBytes.clear();
        submittedBytes = 0;
        playedBytes = 0;
        sampleOffset = 0;
        baseBytes = pendingSkipBytes;
    }

    public PlaybackPositionStream(AudioStream delegate) {
        this(delegate, 0.0);
    }

    public PlaybackPositionStream(AudioStream delegate, double initialSeconds) {
        this.delegate = delegate;
        int frameSize = Math.max(1, getFormat().getFrameSize());
        this.baseBytes = Math.max(0L, Math.round(initialSeconds
                * getFormat().getSampleRate() * frameSize));
    }

    @Override
    public javax.sound.sampled.AudioFormat getFormat() {
        return delegate.getFormat();
    }

    @Override
    public synchronized ByteBuffer read(int targetBytes) throws IOException {
        // A seek can consume an entire decoder buffer. Keep reading until a
        // non-empty buffer is available, while treating null as a real EOF.
        for (;;) {
            ByteBuffer buffer = delegate.read(targetBytes);
            if (buffer == null) {
                ended = true;
                return null;
            }

            if (pendingSkipBytes > 0) {
                int discard = (int) Math.min(pendingSkipBytes, buffer.remaining());
                buffer.position(buffer.position() + discard);
                pendingSkipBytes -= discard;
            }
            int bytes = buffer.remaining();
            if (bytes == 0) continue;
            queuedBufferBytes.add(bytes);
            submittedBytes += bytes;
            return buffer;
        }
    }

    public synchronized void onBuffersProcessed(int count) {
        for (int i = 0; i < count; i++) {
            Integer bytes = queuedBufferBytes.poll();
            if (bytes == null) break;
            playedBytes += bytes;
        }
        sampleOffset = 0;
    }

    public synchronized void updateSampleOffset(int sampleOffset) {
        this.sampleOffset = Math.max(0, sampleOffset);
    }

    public synchronized double elapsedSeconds() {
        int frameSize = Math.max(1, getFormat().getFrameSize());
        long offsetBytes = (long) sampleOffset * frameSize;
        long position = Math.min(baseBytes + submittedBytes, baseBytes + playedBytes + offsetBytes);
        return position / (double) frameSize / getFormat().getSampleRate();
    }

    public synchronized int decodedDurationSeconds() {
        if (!ended) return 0;
        int frameSize = Math.max(1, getFormat().getFrameSize());
        return (int) Math.round((baseBytes + submittedBytes) / (double) frameSize / getFormat().getSampleRate());
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        delegate.close();
    }
}
