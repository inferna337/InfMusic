package ru.inferna.music.audio;

import net.minecraft.client.sound.AudioStream;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Keeps decoded PCM for one playing track so new playback cursors can seek
 * without reopening the network stream or decoding the track again.
 */
public final class SeekableAudioSource {
    private final AudioStream decoder;
    private byte[] pcm = new byte[64 * 1024];
    private int length;
    private boolean ended;
    private boolean closed;

    public SeekableAudioSource(AudioStream decoder) {
        this.decoder = decoder;
    }

    public AudioFormat getFormat() {
        return decoder.getFormat();
    }

    public synchronized AudioStream openAtSeconds(double seconds) {
        long frameBytes = Math.max(1, getFormat().getFrameSize());
        long bytePosition = Math.max(0, Math.round(seconds * getFormat().getSampleRate() * frameBytes));
        return new Cursor(bytePosition - bytePosition % frameBytes);
    }

    private synchronized ByteBuffer read(long position, int targetBytes) throws IOException {
        if (closed) return null;
        long requestedEnd = position + Math.max(1, targetBytes);
        while (!ended && length < requestedEnd) {
            ByteBuffer decoded = decoder.read(Math.max(targetBytes, 16 * 1024));
            if (decoded == null) {
                ended = true;
                break;
            }
            int count = decoded.remaining();
            ensureCapacity(length + count);
            decoded.get(pcm, length, count);
            length += count;
        }
        if (position >= length) return null;
        int count = (int) Math.min(targetBytes, length - position);
        ByteBuffer result = BufferUtils.createByteBuffer(count);
        result.put(pcm, (int) position, count).flip();
        return result;
    }

    private void ensureCapacity(int required) {
        if (required <= pcm.length) return;
        int capacity = pcm.length;
        while (capacity < required) capacity = Math.max(capacity * 2, required);
        pcm = Arrays.copyOf(pcm, capacity);
    }

    public synchronized int durationSeconds() {
        int frameBytes = Math.max(1, getFormat().getFrameSize());
        return Math.round(length / (float) frameBytes / getFormat().getSampleRate());
    }

    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        decoder.close();
        pcm = new byte[0];
        length = 0;
    }

    private final class Cursor implements AudioStream {
        private long position;
        private boolean cursorClosed;

        private Cursor(long position) {
            this.position = position;
        }

        @Override
        public AudioFormat getFormat() {
            return SeekableAudioSource.this.getFormat();
        }

        @Override
        public ByteBuffer read(int targetBytes) throws IOException {
            if (cursorClosed) return null;
            ByteBuffer data = SeekableAudioSource.this.read(position, targetBytes);
            if (data != null) position += data.remaining();
            return data;
        }

        @Override
        public void close() {
            cursorClosed = true;
        }
    }
}
