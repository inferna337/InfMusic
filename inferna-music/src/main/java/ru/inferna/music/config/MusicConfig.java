package ru.inferna.music.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import ru.inferna.music.api.JamendoTrack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class MusicConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("inferna_music.json");

    public String clientId = "PUT_YOUR_JAMENDO_CLIENT_ID_HERE";
    public String soundCloudClientId = "";
    public String soundCloudClientSecret = "";
    public String ytDlpPath = "yt-dlp";
    public String ffmpegPath = "ffmpeg";
    public float volume = 0.85f;
    public boolean autoOpenPlayer = true;
    public List<JamendoTrack> likedTracks = new ArrayList<>();

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank() && !clientId.startsWith("PUT_YOUR_");
    }

    public boolean isSoundCloudConfigured() {
        return soundCloudClientId != null && !soundCloudClientId.isBlank()
                && soundCloudClientSecret != null && !soundCloudClientSecret.isBlank();
    }

    public static MusicConfig load() {
        try {
            if (Files.exists(FILE)) {
                MusicConfig config = GSON.fromJson(Files.readString(FILE), MusicConfig.class);
                if (config != null) {
                    if (config.soundCloudClientId == null) config.soundCloudClientId = "";
                    if (config.soundCloudClientSecret == null) config.soundCloudClientSecret = "";
                    if (config.ytDlpPath == null || config.ytDlpPath.isBlank()) config.ytDlpPath = "yt-dlp";
                    if (config.ffmpegPath == null || config.ffmpegPath.isBlank()) config.ffmpegPath = "ffmpeg";
                    if (config.likedTracks == null) config.likedTracks = new ArrayList<>();
                    return config;
                }
            }
        } catch (Exception ignored) {
        }
        MusicConfig config = new MusicConfig();
        config.save();
        return config;
    }

    public void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(this));
        } catch (IOException ignored) {
        }
    }
}
