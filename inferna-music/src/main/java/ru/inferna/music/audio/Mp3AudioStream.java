package ru.inferna.music.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import net.minecraft.client.sound.AudioStream;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Adapts JLayer's streaming MP3 decoder to Minecraft's AudioStream API.
 */
public final class Mp3AudioStream implements AudioStream {
    private final Bitstream bitstream;
    private final Decoder decoder;
    private final AudioFormat format;
    private final Runnable onEnd;
    private byte[] pending = new byte[0];
    private int pendingOffset;
    private boolean endOfStream;
    private boolean closed;
    private boolean endNotified;

    public Mp3AudioStream(InputStream input) throws IOException {
        this(input, () -> {});
    }

    public Mp3AudioStream(InputStream input, Runnable onEnd) throws IOException {
        this.bitstream = new Bitstream(input);
        this.decoder = new Decoder();
        this.onEnd = onEnd == null ? () -> {} : onEnd;

        Header header = readFrame();
        if (header == null) {
            close();
            throw new IOException("The MP3 stream contains no audio frames");
        }

        try {
            SampleBuffer sample = decode(header);
            this.format = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    sample.getSampleFrequency(),
                    16,
                    sample.getChannelCount(),
                    sample.getChannelCount() * 2,
                    sample.getSampleFrequency(),
                    false
            );
            this.pending = toPcm(sample);
        } finally {
            bitstream.closeFrame();
        }
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    @Override
    public ByteBuffer read(int targetBytes) throws IOException {
        if (targetBytes <= 0 || closed) {
            return null;
        }

        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(targetBytes);
            while (output.size() < targetBytes && !endOfStream) {
                int copied = copyPending(output, targetBytes - output.size());
                if (output.size() >= targetBytes) break;
                if (copied == 0) decodeNextFrame();
            }

            // Minecraft treats null as end-of-stream. Returning an empty buffer would
            // make OpenAL upload a zero-byte buffer, which can crash native audio code.
            if (output.size() == 0) {
                notifyEnd();
                return null;
            }

            // OpenAL/LWJGL requires a direct buffer. Minecraft's built-in OGG
            // stream uses the same allocation strategy.
            ByteBuffer result = BufferUtils.createByteBuffer(output.size());
            result.put(output.toByteArray()).flip();
            return result;
        } catch (IOException | RuntimeException exception) {
            notifyEnd();
            throw exception;
        }
    }

    private int copyPending(ByteArrayOutputStream output, int maxBytes) {
        int available = pending.length - pendingOffset;
        if (available <= 0) return 0;

        int count = Math.min(available, maxBytes);
        output.write(pending, pendingOffset, count);
        pendingOffset += count;
        if (pendingOffset == pending.length) {
            pending = new byte[0];
            pendingOffset = 0;
        }
        return count;
    }

    private void decodeNextFrame() throws IOException {
        Header header = readFrame();
        if (header == null) {
            endOfStream = true;
            notifyEnd();
            return;
        }

        try {
            pending = toPcm(decode(header));
            pendingOffset = 0;
        } finally {
            bitstream.closeFrame();
        }
    }

    private SampleBuffer decode(Header header) throws IOException {
        try {
            return (SampleBuffer) decoder.decodeFrame(header, bitstream);
        } catch (Exception exception) {
            throw new IOException("Unable to decode MP3 frame", exception);
        }
    }

    private Header readFrame() throws IOException {
        try {
            return bitstream.readFrame();
        } catch (Exception exception) {
            throw new IOException("Unable to read MP3 frame", exception);
        }
    }

    private static byte[] toPcm(SampleBuffer sample) {
        short[] samples = sample.getBuffer();
        int length = sample.getBufferLength();
        byte[] pcm = new byte[length * 2];
        for (int i = 0; i < length; i++) {
            short value = samples[i];
            pcm[i * 2] = (byte) (value & 0xFF);
            pcm[i * 2 + 1] = (byte) ((value >>> 8) & 0xFF);
        }
        return pcm;
    }

    private void notifyEnd() {
        if (endNotified) return;
        endNotified = true;
        onEnd.run();
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            try {
                bitstream.close();
            } catch (Exception exception) {
                throw new IOException("Unable to close MP3 stream", exception);
            }
        }
    }
}
