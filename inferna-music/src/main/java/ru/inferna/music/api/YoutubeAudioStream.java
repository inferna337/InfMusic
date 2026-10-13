package ru.inferna.music.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Downloads a complete YouTube audio file before Minecraft starts decoding it.
 * This prevents a network interruption from ending playback halfway through.
 */
public final class YoutubeAudioStream extends InputStream {
    private static final Logger LOGGER = LoggerFactory.getLogger("InfernaMusic/YouTube");
    private final InputStream audio;
    private final Path temporaryDirectory;
    private boolean closed;

    private YoutubeAudioStream(InputStream audio, Path temporaryDirectory) {
        this.audio = audio;
        this.temporaryDirectory = temporaryDirectory;
    }

    public static YoutubeAudioStream open(
            String url,
            String ytDlpPath,
            String ffmpegPath
    ) throws IOException {
        Path temporaryDirectory = Files.createTempDirectory("inferna-music-youtube-");
        try {
            Path sourceTemplate = temporaryDirectory.resolve("source.%(ext)s");
            Process extractor = YoutubeProcess.start(ytDlpPath, List.of(
                    "--ignore-config",
                    "--no-playlist",
                    "--no-part",
                    "--quiet",
                    "--no-warnings",
                    "--no-progress",
                    "--socket-timeout", "20",
                    "--retries", "5",
                    "--extractor-retries", "5",
                    "--fragment-retries", "infinite",
                    "--file-access-retries", "10",
                    "--retry-sleep", "fragment:linear=1::3",
                    "--retry-sleep", "http:linear=1::3",
                    "--format", "bestaudio/best",
                    "--output", sourceTemplate.toString(),
                    url
            ));
            runToCompletion(extractor, "yt-dlp", Duration.ofMinutes(15));

            Path source = findDownloadedSource(temporaryDirectory);
            if (source == null) {
                throw new IOException("yt-dlp downloaded no audio file");
            }

            Path converted = temporaryDirectory.resolve("audio.mp3");
            Process transcoder = new ProcessBuilder(
                    EmbeddedTools.executable(ffmpegPath, "ffmpeg"),
                    "-hide_banner",
                    "-loglevel", "error",
                    "-y",
                    "-i", source.toString(),
                    "-vn",
                    "-f", "mp3",
                    "-acodec", "libmp3lame",
                    "-ar", "44100",
                    "-ac", "2",
                    converted.toString()
            ).start();
            runToCompletion(transcoder, "ffmpeg", Duration.ofMinutes(5));

            if (!Files.isRegularFile(converted) || Files.size(converted) == 0) {
                throw new IOException("ffmpeg produced no audio file");
            }

            return new YoutubeAudioStream(Files.newInputStream(converted), temporaryDirectory);
        } catch (Exception exception) {
            deleteDirectory(temporaryDirectory);
            if (exception instanceof IOException ioException) throw ioException;
            throw new IOException("Unable to prepare YouTube audio", exception);
        }
    }

    private static void runToCompletion(Process process, String name, Duration timeout)
            throws IOException, InterruptedException {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        Thread errorReader = new Thread(() -> {
            try (InputStream input = process.getErrorStream()) {
                input.transferTo(errors);
            } catch (IOException ignored) {
            }
        }, "InfernaMusic " + name + " errors");
        errorReader.setDaemon(true);
        errorReader.start();
        Thread outputReader = new Thread(() -> {
            try (InputStream output = process.getInputStream()) {
                output.transferTo(java.io.OutputStream.nullOutputStream());
            } catch (IOException ignored) {
            }
        }, "InfernaMusic " + name + " output");
        outputReader.setDaemon(true);
        outputReader.start();

        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException(name + " timed out");
        }
        errorReader.join(2000L);
        outputReader.join(2000L);

        if (process.exitValue() != 0) {
            String message = errors.toString(StandardCharsets.UTF_8).trim();
            if (message.length() > 500) message = message.substring(message.length() - 500);
            LOGGER.warn("{} exited with code {}: {}", name, process.exitValue(), message);
            throw new IOException(name + " exited with code " + process.exitValue()
                    + (message.isBlank() ? "" : ": " + message));
        }
    }

    private static Path findDownloadedSource(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equals("audio.mp3"))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static void deleteDirectory(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted((left, right) -> right.compareTo(left))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    @Override
    public int read() throws IOException {
        int value = audio.read();
        if (value < 0) close();
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int count = audio.read(buffer, offset, length);
        if (count < 0) close();
        return count;
    }

    @Override
    public long skip(long count) throws IOException {
        return audio.skip(count);
    }

    @Override
    public int available() throws IOException {
        return audio.available();
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            audio.close();
        } finally {
            deleteDirectory(temporaryDirectory);
        }
    }
}
