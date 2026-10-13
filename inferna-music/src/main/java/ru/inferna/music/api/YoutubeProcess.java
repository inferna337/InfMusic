package ru.inferna.music.api;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class YoutubeProcess {
    private static final Logger LOGGER = LoggerFactory.getLogger("InfernaMusic/YouTube");
    private YoutubeProcess() {
    }

    static Process start(String executable, List<String> arguments) throws IOException {
        IOException firstFailure = null;
        List<String> candidates = candidates(executable);
        if (isYoutubeExecutable(executable)) {
            String bundled = EmbeddedTools.executable(executable, "yt-dlp");
            if (bundled != null) candidates.add(0, bundled);
        }

        for (String candidate : candidates) {
            List<String> command = new ArrayList<>(arguments.size() + 1);
            command.add(candidate);
            command.addAll(arguments);
            try {
                return new ProcessBuilder(command).start();
            } catch (IOException exception) {
                LOGGER.debug("Unable to start YouTube extractor '{}'", candidate, exception);
                if (firstFailure == null) firstFailure = exception;
            }
        }

        if (isYoutubeExecutable(executable)) {
            List<String> pythonCommand = new ArrayList<>(arguments.size() + 3);
            pythonCommand.add("python3");
            pythonCommand.add("-m");
            pythonCommand.add("yt_dlp");
            pythonCommand.addAll(arguments);
            try {
                return new ProcessBuilder(pythonCommand).start();
            } catch (IOException ignored) {
                // Keep the original executable error because it is more useful to the caller.
            }
        }

        LOGGER.warn("YouTube extractor is unavailable. Configure ytDlpPath or install yt-dlp.");
        throw firstFailure == null
                ? new IOException("Unable to start " + executable)
                : firstFailure;
    }

    private static List<String> candidates(String executable) {
        List<String> candidates = new ArrayList<>();
        candidates.add(executable);
        if (!executable.startsWith("./") && executable.contains("/")) {
            candidates.add("./" + executable);
        }
        if (isYoutubeExecutable(executable)) {
            candidates.add("./yt-dlp");
            candidates.add("run/yt-dlp");
            candidates.add("./run/yt-dlp");
            Path gameDir = FabricLoader.getInstance().getGameDir();
            candidates.add(gameDir.resolve("yt-dlp").toString());
            candidates.add(gameDir.resolve("run").resolve("yt-dlp").toString());
        }
        return candidates;
    }

    private static boolean isYoutubeExecutable(String executable) {
        try {
            return "yt-dlp".equals(Path.of(executable).getFileName().toString());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

}
