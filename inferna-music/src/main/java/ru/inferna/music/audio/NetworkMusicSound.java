package ru.inferna.music.audio;

import net.fabricmc.fabric.api.client.sound.v1.FabricSoundInstance;
import net.minecraft.client.sound.AudioStream;
import net.minecraft.client.sound.OggAudioStream;
import net.minecraft.client.sound.SoundLoader;
import net.minecraft.client.sound.AbstractSoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.inferna.music.api.JamendoClient;
import ru.inferna.music.api.JamendoTrack;

import java.io.InputStream;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;

public final class NetworkMusicSound extends AbstractSoundInstance implements FabricSoundInstance {
    private static final Identifier SOUND_ID = Identifier.of("inferna_music", "network_music");
    private static final Logger LOGGER = LoggerFactory.getLogger("InfernaMusic/Audio");
    private final JamendoClient api;
    private volatile JamendoTrack track;
    private final Consumer<NetworkMusicSound> onStart;
    private final Consumer<NetworkMusicSound> onEnd;
    private volatile PlaybackPositionStream playbackStream;
    private volatile double pendingSeekSeconds;
    private volatile SeekableAudioSource sharedSource;
    private volatile boolean cancelled;
    private volatile boolean loading;
    private volatile long requestVersion;

    public NetworkMusicSound(JamendoClient api, JamendoTrack track, float volume) {
        this(api, track, volume, ignored -> {}, ignored -> {});
    }

    public NetworkMusicSound(
            JamendoClient api,
            JamendoTrack track,
            float volume,
            Consumer<NetworkMusicSound> onStart,
            Consumer<NetworkMusicSound> onEnd
    ) {
        this(api, track, volume, onStart, onEnd, null);
    }

    public NetworkMusicSound(
            JamendoClient api,
            JamendoTrack track,
            float volume,
            Consumer<NetworkMusicSound> onStart,
            Consumer<NetworkMusicSound> onEnd,
            SeekableAudioSource sharedSource
    ) {
        // RECORDS is separate from the vanilla MUSIC slider, so the mod has its own volume.
        super(SOUND_ID, SoundCategory.RECORDS, Random.create());
        this.api = api;
        this.track = track;
        this.onStart = onStart == null ? ignored -> {} : onStart;
        this.onEnd = onEnd == null ? ignored -> {} : onEnd;
        this.repeat = false;
        this.relative = true;
        this.volume = volume;
        this.pitch = 1.0f;
        this.sharedSource = sharedSource;
    }

    public JamendoTrack track() {
        return track;
    }

    /** Retargets an in-flight load without adding another SoundInstance to SoundSystem. */
    public synchronized boolean retarget(JamendoTrack nextTrack) {
        if (cancelled || playbackStream != null || !loading) return false;
        track = nextTrack;
        sharedSource = null;
        requestVersion++;
        return true;
    }

    public double elapsedSeconds() {
        PlaybackPositionStream stream = playbackStream;
        return stream == null ? 0 : stream.elapsedSeconds();
    }

    public int decodedDurationSeconds() {
        PlaybackPositionStream stream = playbackStream;
        return stream == null ? 0 : stream.decodedDurationSeconds();
    }

    public void seekSeconds(double seconds) {
        pendingSeekSeconds = Math.max(0.0, seconds);
        PlaybackPositionStream stream = playbackStream;
        if (stream != null) stream.seekSeconds(pendingSeekSeconds);
    }

    public boolean hasPlaybackStream() {
        return playbackStream != null;
    }

    public SeekableAudioSource sharedSource() {
        return sharedSource;
    }

    public void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
    }

    /** Prevents a late async decoder completion from starting this sound again. */
    public void cancel() {
        cancelled = true;
        PlaybackPositionStream stream = playbackStream;
        if (stream != null) {
            try {
                stream.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public CompletableFuture<AudioStream> getAudioStream(
            SoundLoader loader,
            Identifier id,
            boolean repeatInstantly
    ) {
        if (cancelled) return CompletableFuture.failedFuture(new CancellationException("Sound was stopped"));
        if (sharedSource != null) {
            if (cancelled) return CompletableFuture.failedFuture(new CancellationException("Sound was stopped"));
            PlaybackPositionStream tracked = new PlaybackPositionStream(
                    sharedSource.openAtSeconds(pendingSeekSeconds), pendingSeekSeconds);
            playbackStream = tracked;
            onStart.accept(this);
            return CompletableFuture.completedFuture(tracked);
        }
        loading = true;
        return loadLatestRequest().whenComplete((stream, error) -> {
            loading = false;
            if (error != null && !cancelled) onEnd.accept(this);
        });
    }

    private CompletableFuture<AudioStream> loadLatestRequest() {
        if (cancelled) return CompletableFuture.failedFuture(new CancellationException("Sound was stopped"));
        long version = requestVersion;
        JamendoTrack requestedTrack = track;
        return api.openStream(requestedTrack).thenCompose(input -> {
            if (cancelled) {
                closeInput(input);
                return CompletableFuture.failedFuture(new CancellationException("Sound was stopped"));
            }
            if (version != requestVersion) {
                closeInput(input);
                return loadLatestRequest();
            }

            SeekableAudioSource source = null;
            try {
                AudioStream decoded = "mp3".equalsIgnoreCase(requestedTrack.audioFormat())
                        ? new Mp3AudioStream(input)
                        : new OggAudioStream(input);
                source = new SeekableAudioSource(decoded);
                if (cancelled) {
                    source.close();
                    return CompletableFuture.failedFuture(new CancellationException("Sound was stopped"));
                }
                if (version != requestVersion) {
                    source.close();
                    return loadLatestRequest();
                }

                sharedSource = source;
                AudioStream tracked = new PlaybackPositionStream(
                        source.openAtSeconds(pendingSeekSeconds), pendingSeekSeconds);
                playbackStream = (PlaybackPositionStream) tracked;
                onStart.accept(this);
                return CompletableFuture.completedFuture(tracked);
            } catch (Exception exception) {
                if (source == null) closeInput(input);
                if (version != requestVersion) return loadLatestRequest();
                if (exception instanceof CancellationException cancellation) {
                    return CompletableFuture.failedFuture(cancellation);
                }
                LOGGER.warn("Unable to decode track '{}' from {}", requestedTrack.name(), requestedTrack.source(), exception);
                return CompletableFuture.failedFuture(new RuntimeException(
                        "Unable to decode the audio stream; use a direct OGG or MP3 URL", exception));
            }
        });
    }

    private static void closeInput(InputStream input) {
        try { input.close(); } catch (Exception ignored) {}
    }
}
