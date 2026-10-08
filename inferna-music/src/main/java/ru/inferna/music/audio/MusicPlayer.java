package ru.inferna.music.audio;

import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundCategory;
import ru.inferna.music.api.JamendoClient;
import ru.inferna.music.api.JamendoTrack;
import ru.inferna.music.config.MusicConfig;

import java.util.ArrayList;
import java.util.List;

public final class MusicPlayer {
    private final MinecraftClient client;
    private final JamendoClient api;
    private final MusicConfig config;
    private NetworkMusicSound currentSound;
    private JamendoTrack currentTrack;
    private int frozenElapsedSeconds;
    private boolean playbackStarted;
    private boolean paused;
    private List<JamendoTrack> playlist;
    private int playlistIndex = -1;

    public MusicPlayer(MinecraftClient client, JamendoClient api, MusicConfig config) {
        this.client = client;
        this.api = api;
        this.config = config;
    }

    public void play(JamendoTrack track) {
        playlist = null;
        playlistIndex = -1;
        startTrack(track);
    }

    public void playPlaylist(List<JamendoTrack> tracks, int startIndex) {
        if (tracks == null || tracks.isEmpty()) return;
        int index = Math.max(0, Math.min(startIndex, tracks.size() - 1));
        playlist = new ArrayList<>(tracks);
        playlistIndex = index;
        startTrack(playlist.get(playlistIndex));
    }

    private void startTrack(JamendoTrack track) {
        if (currentSound != null
                && !currentSound.hasPlaybackStream()
                && currentSound.retarget(track)) {
            currentTrack = track;
            frozenElapsedSeconds = 0;
            playbackStarted = false;
            paused = false;
            return;
        }
        stopCurrentSound();
        currentTrack = track;
        currentSound = new NetworkMusicSound(
                api,
                track,
                config.volume,
                this::handleStreamStarted,
                this::handleStreamEnded
        );
        frozenElapsedSeconds = 0;
        playbackStarted = false;
        paused = false;
        client.getSoundManager().play(currentSound);
    }

    public void stop() {
        playlist = null;
        playlistIndex = -1;
        stopCurrentSound();
        currentTrack = null;
        frozenElapsedSeconds = 0;
        playbackStarted = false;
        paused = false;
    }

    private void stopCurrentSound() {
        if (currentSound != null) {
            currentSound.cancel();
            client.getSoundManager().stop(currentSound);
            currentSound = null;
        }
    }

    public boolean isPlaying() {
        return currentSound != null
                && !paused
                && client.getSoundManager().isPlaying(currentSound);
    }

    public boolean isPaused() {
        return currentSound != null && paused;
    }

    public void pause() {
        if (currentSound == null || paused) return;
        paused = true;
        ((SoundManagerAccess) client.getSoundManager()).infernaMusic$pause(currentSound);
    }

    public void resume() {
        if (currentSound == null || !paused) return;
        paused = false;
        ((SoundManagerAccess) client.getSoundManager()).infernaMusic$resume(currentSound);
    }

    public void togglePause() {
        if (paused) resume();
        else pause();
    }

    public boolean hasActiveTrack() {
        return currentSound != null;
    }

    /**
     * Catches natural completion for streams that do not expose an EOF callback.
     */
    public void tick() {
        NetworkMusicSound sound = currentSound;
        if (sound != null && paused) {
            // The source can be created asynchronously. Retry until the pause reaches it.
            ((SoundManagerAccess) client.getSoundManager()).infernaMusic$pause(sound);
            return;
        }
        if (sound != null && playbackStarted && !client.getSoundManager().isPlaying(sound)) {
            finish(sound);
        }
    }

    public JamendoTrack currentTrack() {
        return currentTrack;
    }

    public int elapsedSeconds() {
        if (currentSound == null) return frozenElapsedSeconds;
        return Math.max(0, (int) currentSound.elapsedSeconds());
    }

    /** Restarts the current stream and discards decoded audio up to the requested position. */
    public void seek(int seconds) {
        if (currentTrack == null) return;
        int total = decodedDurationSeconds();
        if (total <= 0) total = currentTrack.duration();
        int target = Math.max(0, total > 0 ? Math.min(seconds, total) : seconds);
        NetworkMusicSound oldSound = currentSound;
        // While the first network/decode future is still pending, keep the
        // same SoundInstance. Replacing it repeatedly can leave SoundSystem
        // waiting on cancelled futures and eventually stop all new sounds.
        if (oldSound != null && !oldSound.hasPlaybackStream()) {
            oldSound.seekSeconds(target);
            frozenElapsedSeconds = target;
            return;
        }
        SeekableAudioSource source = oldSound == null ? null : oldSound.sharedSource();
        stopCurrentSound();
        currentSound = new NetworkMusicSound(api, currentTrack, config.volume,
                this::handleStreamStarted, this::handleStreamEnded, source);
        currentSound.seekSeconds(target);
        frozenElapsedSeconds = target;
        playbackStarted = false;
        paused = false;
        client.getSoundManager().play(currentSound);
    }

    private void handleStreamStarted(NetworkMusicSound sound) {
        client.execute(() -> {
            if (currentSound != sound || playbackStarted) return;
            playbackStarted = true;
        });
    }

    private void handleStreamEnded(NetworkMusicSound sound) {
        client.execute(() -> {
            if (currentSound != sound) return;
            finish(sound);
        });
    }

    private void finish(NetworkMusicSound sound) {
        if (currentSound != sound) return;
        frozenElapsedSeconds = elapsedSeconds();
        playbackStarted = false;
        paused = false;
        currentSound = null;

        if (playlist != null && playlistIndex + 1 < playlist.size()) {
            playlistIndex++;
            startTrack(playlist.get(playlistIndex));
            return;
        }

        playlist = null;
        playlistIndex = -1;
        currentTrack = null;
    }

    public int decodedDurationSeconds() {
        return currentSound == null ? 0 : currentSound.decodedDurationSeconds();
    }

    public void setVolume(float volume) {
        config.volume = Math.max(0f, Math.min(1f, volume));
        config.save();
        if (currentSound != null) {
            currentSound.setVolume(config.volume);
            client.getSoundManager().refreshSoundVolumes(SoundCategory.RECORDS);
        }
    }

    public float volume() {
        return config.volume;
    }
}
