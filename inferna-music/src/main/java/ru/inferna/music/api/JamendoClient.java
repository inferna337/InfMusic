package ru.inferna.music.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class JamendoClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("InfernaMusic/Search");
    private static final String API = "https://api.jamendo.com/v3.0";
    private static final String ARCHIVE_API = "https://archive.org";
    private static final String OPENVERSE_API = "https://api.openverse.org/v1/audio/";
    private static final String OPENWHYD_API = "https://openwhyd.org/search";
    private static final String SOUNDCLOUD_API = "https://api.soundcloud.com";
    private static final String SOUNDCLOUD_OAUTH = "https://secure.soundcloud.com/oauth/token";
    private static final int RESULT_LIMIT = 20;
    private static final ExecutorService YOUTUBE_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "InfernaMusic YouTube metadata");
        thread.setDaemon(true);
        return thread;
    });

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final String clientId;
    private final String soundCloudClientId;
    private final String soundCloudClientSecret;
    private final String ytDlpPath;
    private final String ffmpegPath;
    private volatile String soundCloudToken;
    private volatile long soundCloudTokenExpiresAt;
    private CompletableFuture<String> soundCloudTokenFuture;

    public JamendoClient(String clientId) {
        this(clientId, "", "", "yt-dlp", "ffmpeg");
    }

    public JamendoClient(String clientId, String soundCloudClientId, String soundCloudClientSecret) {
        this(clientId, soundCloudClientId, soundCloudClientSecret, "yt-dlp", "ffmpeg");
    }

    public JamendoClient(
            String clientId,
            String soundCloudClientId,
            String soundCloudClientSecret,
            String ytDlpPath,
            String ffmpegPath
    ) {
        this.clientId = clientId == null ? "" : clientId;
        this.soundCloudClientId = soundCloudClientId == null ? "" : soundCloudClientId.trim();
        this.soundCloudClientSecret = soundCloudClientSecret == null ? "" : soundCloudClientSecret.trim();
        this.ytDlpPath = ytDlpPath == null || ytDlpPath.isBlank() ? "yt-dlp" : ytDlpPath.trim();
        this.ffmpegPath = ffmpegPath == null || ffmpegPath.isBlank() ? "ffmpeg" : ffmpegPath.trim();
    }

    public CompletableFuture<List<JamendoTrack>> search(String query) {
        String trimmed = query.trim();
        if (isSoundCloudUrl(trimmed)) {
            if (!isSoundCloudConfigured()) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("SoundCloud client ID and secret are not configured")
                );
            }
            return resolveSoundCloud(trimmed)
                    .thenApply(track -> track == null ? List.of() : List.of(track));
        }
        if (isYouTubeUrl(trimmed)) {
            return resolveYoutubeDuration(youtubeTrack(trimmed))
                    .thenApply(track -> track == null ? List.of() : List.of(track));
        }
        if (trimmed.startsWith("https://") || trimmed.startsWith("http://")) {
            return CompletableFuture.completedFuture(List.of(directTrack(trimmed)));
        }

        CompletableFuture<List<JamendoTrack>> jamendo = clientId.isBlank()
                ? CompletableFuture.completedFuture(List.of())
                : searchJamendo(trimmed).exceptionally(error -> List.of());
        CompletableFuture<List<JamendoTrack>> archive = searchInternetArchive(trimmed)
                .exceptionally(error -> List.of());
        CompletableFuture<List<JamendoTrack>> openverseOgg = searchOpenverse(trimmed, "ogg")
                .exceptionally(error -> List.of());
        CompletableFuture<List<JamendoTrack>> openverseMp3 = searchOpenverse(trimmed, "mp3")
                .exceptionally(error -> List.of());
        CompletableFuture<List<JamendoTrack>> soundCloud = isSoundCloudConfigured()
                ? searchSoundCloud(trimmed).exceptionally(error -> List.of())
                : CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<JamendoTrack>> openwhyd = searchOpenwhyd(trimmed)
                .exceptionally(error -> List.of());
        CompletableFuture<List<JamendoTrack>> youtube = searchYoutube(trimmed);

        return CompletableFuture.allOf(jamendo, archive, openverseOgg, openverseMp3, soundCloud, openwhyd, youtube)
                .thenApply(ignored -> {
                    List<JamendoTrack> result = new ArrayList<>(
                            jamendo.join().size()
                                    + archive.join().size()
                                    + openverseOgg.join().size()
                                    + openverseMp3.join().size()
                                    + soundCloud.join().size()
                                    + openwhyd.join().size()
                                    + youtube.join().size()
                    );
                    // YouTube already returns results ordered by its relevance ranking.
                    result.addAll(youtube.join());
                    result.addAll(jamendo.join());
                    result.addAll(archive.join());
                    result.addAll(openverseOgg.join());
                    result.addAll(openverseMp3.join());
                    result.addAll(soundCloud.join());
                    result.addAll(openwhyd.join());
                    return deduplicate(result);
                });
    }

    private CompletableFuture<List<JamendoTrack>> searchYoutube(String query) {
        return CompletableFuture.supplyAsync(() -> {
            Process process = null;
            try {
                process = YoutubeProcess.start(ytDlpPath, List.of(
                        "--ignore-config",
                        "--no-playlist",
                        "--flat-playlist",
                        "--skip-download",
                        "--dump-single-json",
                        "--quiet",
                        "--no-warnings",
                        "--socket-timeout", "20",
                        "--retries", "2",
                        "--extractor-retries", "2",
                        "ytsearch" + RESULT_LIMIT + ":" + query
                ));
                drainProcessErrors(process);
                Process searchProcess = process;
                CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
                    try (InputStream output = searchProcess.getInputStream()) {
                        return new String(output.readAllBytes(), StandardCharsets.UTF_8);
                    } catch (IOException exception) {
                        throw new RuntimeException(exception);
                    }
                });

                if (!process.waitFor(45, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    outputFuture.cancel(true);
                    LOGGER.debug("Timed out while searching YouTube for '{}'", query);
                    return List.of();
                }

                String json = outputFuture.get(5, TimeUnit.SECONDS);
                if (process.exitValue() != 0 || json.isBlank()) return List.of();
                return parseYoutubeSearch(json);
            } catch (Exception exception) {
                LOGGER.debug("Unable to search YouTube for '{}'", query, exception);
                return List.of();
            } finally {
                if (process != null && process.isAlive()) process.destroy();
            }
        }, YOUTUBE_EXECUTOR);
    }

    private List<JamendoTrack> parseYoutubeSearch(String json) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException exception) {
            LOGGER.debug("YouTube returned invalid search JSON", exception);
            return List.of();
        }

        JsonArray entries;
        if (root.isJsonObject() && root.getAsJsonObject().has("entries")) {
            entries = root.getAsJsonObject().getAsJsonArray("entries");
        } else if (root.isJsonArray()) {
            entries = root.getAsJsonArray();
        } else {
            return List.of();
        }

        List<JamendoTrack> tracks = new ArrayList<>();
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String videoId = getString(item, "id", "");
            if (videoId.isBlank()) continue;

            String url = getString(item, "webpage_url", "");
            if (!isYouTubeUrl(url)) {
                url = "https://www.youtube.com/watch?v=" + videoId;
            }

            int duration = (int) Math.max(0, Math.round(getDouble(item, "duration")));
            if (duration <= 0) continue;

            String title = getString(item, "title", "YouTube track");
            String artist = getString(item, "uploader",
                    getString(item, "channel", "YouTube"));
            String thumbnail = getThumbnail(item);
            tracks.add(new JamendoTrack(
                    videoId.hashCode(),
                    title,
                    artist,
                    "YouTube",
                    duration,
                    thumbnail,
                    url,
                    "mp3",
                    "",
                    true,
                    url,
                    "YouTube"
            ));
        }
        return tracks;
    }

    private static String getThumbnail(JsonObject object) {
        String direct = getString(object, "thumbnail", "");
        if (!direct.isBlank()) return direct;
        if (!object.has("thumbnails") || !object.get("thumbnails").isJsonArray()) return "";

        JsonArray thumbnails = object.getAsJsonArray("thumbnails");
        String fallback = "";
        for (JsonElement element : thumbnails) {
            if (!element.isJsonObject()) continue;
            String url = getString(element.getAsJsonObject(), "url", "");
            if (!url.isBlank()) fallback = url;
        }
        return fallback;
    }

    public CompletableFuture<InputStream> openStream(JamendoTrack track) {
        if ("SoundCloud".equalsIgnoreCase(track.source())) {
            return openSoundCloudStream(track);
        }
        if (isYouTubeTrack(track)) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return YoutubeAudioStream.open(track.audioUrl(), ytDlpPath, ffmpegPath);
                } catch (IOException exception) {
                    throw new RuntimeException(
                            "YouTube playback requires yt-dlp and ffmpeg in PATH or configured paths",
                            exception
                    );
                }
            });
        }

        String url = track.audioUrl();
        if (url == null || url.isBlank()) {
            url = API + "/tracks/file/?client_id=" + clientId
                    + "&id=" + track.id() + "&audioformat=ogg&action=stream";
        }
        return openHttpStream(url, "audio/ogg,audio/mpeg,audio/*;q=0.9,*/*;q=0.1");
    }

    private CompletableFuture<List<JamendoTrack>> searchJamendo(String query) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = API + "/tracks/?client_id=" + clientId
                + "&format=json&limit=" + RESULT_LIMIT + "&audioformat=ogg&imagesize=200"
                + "&namesearch=" + encoded;

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET()
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    requireSuccess(response.statusCode(), "Jamendo");
                    return parseTracks(response.body());
                });
    }

    private CompletableFuture<List<JamendoTrack>> searchInternetArchive(String query) {
        String encodedQuery = URLEncoder.encode(
                "mediatype:audio AND (format:\"Ogg Vorbis\" OR format:Ogg OR format:MP3 OR format:\"MPEG Audio\")"
                        + " AND (title:(" + query + ") OR creator:(" + query + ") OR description:(" + query + "))",
                StandardCharsets.UTF_8
        );
        String url = ARCHIVE_API + "/advancedsearch.php?q=" + encodedQuery
                + "&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=description&fl[]=format"
                + "&rows=" + RESULT_LIMIT + "&page=1&output=json";

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET()
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenCompose(response -> {
                    requireSuccess(response.statusCode(), "Internet Archive");
                    return loadArchiveTracks(response.body());
                });
    }

    private CompletableFuture<List<JamendoTrack>> searchOpenverse(String query, String extension) {
        String url = OPENVERSE_API
                + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&source=wikimedia_audio&extension=" + extension
                + "&page_size=" + RESULT_LIMIT + "&page=1";

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("User-Agent", "InfernaMusic/1.0")
                .GET()
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    requireSuccess(response.statusCode(), "Openverse");
                    return parseOpenverseTracks(response.body());
                });
    }

    private CompletableFuture<List<JamendoTrack>> searchSoundCloud(String query) {
        return soundCloudToken().thenCompose(token -> {
            String url = SOUNDCLOUD_API + "/tracks?q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&limit=" + RESULT_LIMIT + "&linked_partitioning=true&access=playable";
            HttpRequest request = authorizedRequest(url, token).GET().build();
            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        requireSuccess(response.statusCode(), "SoundCloud");
                        return parseSoundCloudTracks(response.body());
                    });
        });
    }

    private CompletableFuture<List<JamendoTrack>> searchOpenwhyd(String query) {
        String url = OPENWHYD_API
                + "?context=addTrack&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("User-Agent", "InfernaMusic/1.0")
                .GET()
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    requireSuccess(response.statusCode(), "Openwhyd");
                    return parseOpenwhydTracks(response.body());
                })
                .thenCompose(this::enrichYoutubeDurations);
    }

    private CompletableFuture<List<JamendoTrack>> enrichYoutubeDurations(List<JamendoTrack> tracks) {
        List<CompletableFuture<JamendoTrack>> futures = tracks.stream()
                .map(track -> isYouTubeTrack(track)
                        ? resolveYoutubeDuration(track)
                        : CompletableFuture.completedFuture(track))
                .toList();
        if (futures.isEmpty()) return CompletableFuture.completedFuture(tracks);

        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> futures.stream()
                        .map(CompletableFuture::join)
                        .filter(track -> track != null)
                        .toList());
    }

    private CompletableFuture<JamendoTrack> resolveYoutubeDuration(JamendoTrack track) {
        return CompletableFuture.supplyAsync(() -> {
            for (int attempt = 1; attempt <= 2; attempt++) {
                JamendoTrack resolved = resolveYoutubeDurationOnce(track);
                if (resolved != null) return resolved;
                if (attempt < 2) {
                    try {
                        Thread.sleep(400L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
            LOGGER.debug("Skipping unavailable YouTube track {}", track.audioUrl());
            return null;
        }, YOUTUBE_EXECUTOR);
    }

    private JamendoTrack resolveYoutubeDurationOnce(JamendoTrack track) {
            Process process = null;
            try {
                process = YoutubeProcess.start(ytDlpPath, List.of(
                        "--ignore-config",
                        "--no-playlist",
                        "--skip-download",
                        "--dump-single-json",
                        "--quiet",
                        "--no-warnings",
                        "--socket-timeout", "20",
                        "--retries", "1",
                        "--extractor-retries", "1",
                        track.audioUrl()
                ));
                drainProcessErrors(process);
                Process metadataProcess = process;
                CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
                    try (InputStream output = metadataProcess.getInputStream()) {
                        return new String(output.readAllBytes(), StandardCharsets.UTF_8);
                    } catch (IOException exception) {
                        throw new RuntimeException(exception);
                    }
                });
                if (!process.waitFor(35, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    outputFuture.cancel(true);
                    LOGGER.debug("Timed out while resolving YouTube metadata for {}", track.audioUrl());
                    return null;
                }
                String json = outputFuture.get(5, TimeUnit.SECONDS);
                if (process.exitValue() != 0 || json.isBlank()) return null;

                JsonElement parsed = JsonParser.parseString(json);
                if (!parsed.isJsonObject()) return null;
                JsonObject metadata = parsed.getAsJsonObject();
                int duration = (int) Math.max(0, Math.round(getDouble(metadata, "duration")));
                if (duration <= 0) return null;
                String title = getString(metadata, "title", track.name());
                String artist = getString(metadata, "artist",
                        getString(metadata, "uploader", track.artist()));
                String thumbnail = getString(metadata, "thumbnail", track.imageUrl());

                return new JamendoTrack(
                        track.id(),
                        title,
                        artist,
                        track.album(),
                        duration,
                        thumbnail,
                        track.audioUrl(),
                        track.audioFormat(),
                        track.licenseUrl(),
                        track.audioDownloadAllowed(),
                        track.shortUrl(),
                        track.source()
                );
            } catch (Exception exception) {
                LOGGER.debug("Unable to resolve YouTube metadata for {}", track.audioUrl(), exception);
                return null;
            } finally {
                if (process != null && process.isAlive()) process.destroy();
            }
    }

    private static void drainProcessErrors(Process process) {
        Thread drain = new Thread(() -> {
            try (InputStream errors = process.getErrorStream()) {
                errors.transferTo(OutputStream.nullOutputStream());
            } catch (IOException ignored) {
            }
        }, "InfernaMusic yt-dlp metadata errors");
        drain.setDaemon(true);
        drain.start();
    }

    private List<JamendoTrack> parseOpenwhydTracks(String json) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonArray()) return List.of();

        List<JamendoTrack> tracks = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String eId = getString(item, "eId", "");
            String pageUrl = normalizeOpenwhydUrl(getString(item, "url", ""));
            String name = getString(item, "name", "Openwhyd track");
            String artist = getString(item, "uNm", "Openwhyd");
            String image = getString(item, "img", "");
            JamendoTrack track = parseOpenwhydTrack(
                    eId,
                    pageUrl,
                    name,
                    artist,
                    image,
                    getString(item, "id", pageUrl)
            );
            if (track != null) tracks.add(track);
        }
        return tracks;
    }

    private JamendoTrack parseOpenwhydTrack(
            String eId,
            String pageUrl,
            String name,
            String artist,
            String image,
            String stableId
    ) {
        if (eId.startsWith("/sc/")) {
            String soundCloudUrl = eId.contains("#")
                    ? eId.substring(eId.indexOf('#') + 1)
                    : pageUrl;
            return new JamendoTrack(
                    stableId.hashCode(),
                    name,
                    artist,
                    "",
                    0,
                    image,
                    soundCloudUrl,
                    "mp3",
                    "",
                    false,
                    pageUrl,
                    "SoundCloud"
            );
        }

        if (eId.startsWith("/fi/")) {
            String directUrl = eId.substring(4);
            String format = inferFormat(directUrl);
            if (format.isBlank()) return null;
            return new JamendoTrack(
                    stableId.hashCode(),
                    name,
                    artist,
                    "",
                    0,
                    image,
                    directUrl,
                    format,
                    "",
                    true,
                    pageUrl,
                    "Openwhyd"
            );
        }

        if (eId.startsWith("/yt/")) {
            String videoId = eId.substring(4);
            String youtubeUrl = isYouTubeUrl(pageUrl)
                    ? pageUrl
                    : "https://www.youtube.com/watch?v=" + videoId;
            return new JamendoTrack(
                    stableId.hashCode(),
                    name,
                    artist,
                    "YouTube audio",
                    0,
                    image,
                    youtubeUrl,
                    "mp3",
                    "",
                    true,
                    youtubeUrl,
                    "Openwhyd / YouTube"
            );
        }

        return null;
    }

    private static String normalizeOpenwhydUrl(String value) {
        if (value.startsWith("//")) return "https:" + value;
        return value;
    }

    private CompletableFuture<JamendoTrack> resolveSoundCloud(String url) {
        return soundCloudToken().thenCompose(token -> {
            String endpoint = SOUNDCLOUD_API + "/resolve?url="
                    + URLEncoder.encode(url, StandardCharsets.UTF_8);
            HttpRequest request = authorizedRequest(endpoint, token).GET().build();
            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        requireSuccess(response.statusCode(), "SoundCloud");
                        JsonObject object = JsonParser.parseString(response.body()).getAsJsonObject();
                        return parseSoundCloudTrack(object);
                    });
        });
    }

    private CompletableFuture<InputStream> openSoundCloudStream(JamendoTrack track) {
        return soundCloudToken().thenCompose(token -> openSoundCloudUrl(track.audioUrl(), token));
    }

    private CompletableFuture<InputStream> openSoundCloudUrl(String url, String token) {
        HttpRequest request = authorizedRequest(url, token)
                .header("Accept", "audio/mpeg,audio/*;q=0.9,application/json;q=0.5,*/*;q=0.1")
                .GET()
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenCompose(response -> {
                    requireSuccess(response.statusCode(), "SoundCloud stream");
                    String contentType = response.headers()
                            .firstValue("Content-Type")
                            .orElse("")
                            .toLowerCase();
                    if (!contentType.contains("json")) {
                        return CompletableFuture.completedFuture(response.body());
                    }

                    try (InputStream body = response.body()) {
                        String json = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                        String transcodingUrl = findMp3Transcoding(json);
                        if (transcodingUrl == null) {
                            return CompletableFuture.failedFuture(
                                    new IllegalStateException("SoundCloud did not provide an MP3 stream")
                            );
                        }
                        return openSoundCloudUrl(transcodingUrl, token);
                    } catch (Exception exception) {
                        return CompletableFuture.failedFuture(exception);
                    }
                });
    }

    private CompletableFuture<String> soundCloudToken() {
        if (!isSoundCloudConfigured()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("SoundCloud client ID and secret are not configured")
            );
        }
        synchronized (this) {
            if (soundCloudToken != null && System.currentTimeMillis() < soundCloudTokenExpiresAt) {
                return CompletableFuture.completedFuture(soundCloudToken);
            }
            if (soundCloudTokenFuture != null) return soundCloudTokenFuture;

            String credentials = Base64.getEncoder().encodeToString(
                    (soundCloudClientId + ":" + soundCloudClientSecret).getBytes(StandardCharsets.UTF_8)
            );
            HttpRequest request = HttpRequest.newBuilder(URI.create(SOUNDCLOUD_OAUTH))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Authorization", "Basic " + credentials)
                    .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                    .build();

            soundCloudTokenFuture = http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        requireSuccess(response.statusCode(), "SoundCloud OAuth");
                        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                        String token = getString(json, "access_token", "");
                        if (token.isBlank()) throw new IllegalStateException("SoundCloud returned no access token");
                        long expiresIn = Math.max(60L, getLong(json, "expires_in"));
                        soundCloudToken = token;
                        soundCloudTokenExpiresAt = System.currentTimeMillis() + (expiresIn - 60L) * 1000L;
                        return token;
                    })
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            synchronized (this) {
                                soundCloudTokenFuture = null;
                            }
                        }
                    });
            return soundCloudTokenFuture;
        }
    }

    private List<JamendoTrack> parseOpenverseTracks(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray results = root.has("results") ? root.getAsJsonArray("results") : new JsonArray();
        List<JamendoTrack> tracks = new ArrayList<>();

        for (JsonElement element : results) {
            JsonObject item = element.getAsJsonObject();
            String audioUrl = getString(item, "url", "");
            String extension = getString(item, "extension", "").toLowerCase();
            String fileType = getString(item, "filetype", "").toLowerCase();
            String format = supportedFormat(extension, fileType);
            if (audioUrl.isBlank() || format.isBlank()) continue;

            String title = getString(item, "title", "Open audio");
            String creator = getString(item, "creator", "Unknown creator");
            String licenseUrl = getString(item, "license_url", "");
            String landingUrl = getString(item, "foreign_landing_url",
                    getString(item, "detail_url", audioUrl));
            tracks.add(new JamendoTrack(
                    getString(item, "id", audioUrl).hashCode(),
                    title,
                    creator,
                    "",
                    getMillisAsSeconds(item, "duration"),
                    getString(item, "thumbnail", ""),
                    audioUrl,
                    format,
                    licenseUrl,
                    true,
                    landingUrl,
                    "Openverse"
            ));
        }
        return tracks;
    }

    private CompletableFuture<List<JamendoTrack>> loadArchiveTracks(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject response = root.has("response") ? root.getAsJsonObject("response") : new JsonObject();
        JsonArray docs = response.has("docs") ? response.getAsJsonArray("docs") : new JsonArray();
        List<CompletableFuture<JamendoTrack>> futures = new ArrayList<>();

        for (JsonElement element : docs) {
            JsonObject doc = element.getAsJsonObject();
            String identifier = getString(doc, "identifier", "");
            if (!identifier.isBlank()) futures.add(loadArchiveTrack(identifier, doc));
        }

        if (futures.isEmpty()) return CompletableFuture.completedFuture(List.of());
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> futures.stream()
                        .map(CompletableFuture::join)
                        .filter(track -> track != null)
                        .sorted(Comparator.comparing(JamendoTrack::name, String.CASE_INSENSITIVE_ORDER))
                        .toList());
    }

    private CompletableFuture<JamendoTrack> loadArchiveTrack(String identifier, JsonObject document) {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(ARCHIVE_API + "/metadata/" + encodePathSegment(identifier))
                )
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET()
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() / 100 != 2) return null;
                    return parseArchiveTrack(identifier, document, response.body());
                })
                .exceptionally(error -> null);
    }

    private JamendoTrack parseArchiveTrack(String identifier, JsonObject document, String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray files = root.has("files") ? root.getAsJsonArray("files") : new JsonArray();
        JsonObject selected = null;
        String format = "";

        for (JsonElement element : files) {
            JsonObject file = element.getAsJsonObject();
            format = supportedFormat(
                    getString(file, "name", "").toLowerCase(),
                    getString(file, "format", "").toLowerCase()
            );
            if (!format.isBlank()) {
                selected = file;
                break;
            }
        }
        if (selected == null) return null;

        String title = getString(root, "title", getString(document, "title", identifier));
        String artist = getString(root, "creator", getString(document, "creator", "Internet Archive"));
        if (artist.contains(",")) artist = artist.substring(0, artist.indexOf(',')).trim();
        String fileName = getString(selected, "name", "");
        if (fileName.isBlank()) return null;

        return new JamendoTrack(
                identifier.hashCode(),
                title,
                artist,
                "",
                getInt(selected, "length"),
                "",
                ARCHIVE_API + "/download/" + encodePathSegment(identifier) + "/" + encodePath(fileName),
                format,
                getString(root, "licenseurl", ""),
                true,
                ARCHIVE_API + "/details/" + encodePathSegment(identifier),
                "Internet Archive"
        );
    }

    private List<JamendoTrack> parseSoundCloudTracks(String json) {
        JsonElement root = JsonParser.parseString(json);
        JsonArray collection;
        if (root.isJsonArray()) {
            collection = root.getAsJsonArray();
        } else {
            JsonObject object = root.getAsJsonObject();
            collection = object.has("collection") ? object.getAsJsonArray("collection") : new JsonArray();
        }

        List<JamendoTrack> tracks = new ArrayList<>();
        for (JsonElement element : collection) {
            if (element.isJsonObject()) {
                JamendoTrack track = parseSoundCloudTrack(element.getAsJsonObject());
                if (track != null) tracks.add(track);
            }
        }
        return tracks;
    }

    private JamendoTrack parseSoundCloudTrack(JsonObject object) {
        long id = getLong(object, "id");
        String streamUrl = getString(object, "stream_url", "");
        if (streamUrl.isBlank() && id > 0) {
            streamUrl = SOUNDCLOUD_API + "/tracks/" + id + "/stream";
        }
        if (streamUrl.isBlank()) return null;

        JsonObject user = object.has("user") && object.get("user").isJsonObject()
                ? object.getAsJsonObject("user")
                : new JsonObject();
        return new JamendoTrack(
                id,
                getString(object, "title", "SoundCloud track"),
                getString(user, "username", "SoundCloud"),
                "",
                (int) Math.max(0, getLong(object, "duration") / 1000L),
                getString(object, "artwork_url", ""),
                streamUrl,
                "mp3",
                getString(object, "license", ""),
                getBoolean(object, "downloadable", false),
                getString(object, "permalink_url", streamUrl),
                "SoundCloud"
        );
    }

    private String findMp3Transcoding(String json) {
        JsonElement root = JsonParser.parseString(json);
        JsonArray transcodings;
        if (root.isJsonArray()) {
            transcodings = root.getAsJsonArray();
        } else if (root.isJsonObject() && root.getAsJsonObject().has("transcodings")) {
            transcodings = root.getAsJsonObject().getAsJsonArray("transcodings");
        } else {
            return null;
        }

        String fallback = null;
        for (JsonElement element : transcodings) {
            if (!element.isJsonObject()) continue;
            JsonObject transcoding = element.getAsJsonObject();
            JsonObject format = transcoding.has("format") && transcoding.get("format").isJsonObject()
                    ? transcoding.getAsJsonObject("format")
                    : new JsonObject();
            String url = getString(transcoding, "url", "");
            String mime = getString(format, "mime_type", "").toLowerCase();
            String protocol = getString(format, "protocol", "").toLowerCase();
            if (mime.contains("audio/mpeg") && protocol.contains("progressive")) return url;
            if (mime.contains("audio/mpeg") && fallback == null) fallback = url;
        }
        return fallback;
    }

    private static JamendoTrack directTrack(String url) {
        String name = url;
        int slash = name.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < name.length()) name = name.substring(slash + 1);
        int query = name.indexOf('?');
        if (query >= 0) name = name.substring(0, query);
        if (name.isBlank()) name = "Direct audio stream";

        return new JamendoTrack(
                url.hashCode(),
                name,
                "Direct URL",
                "",
                0,
                "",
                url,
                inferFormat(url),
                "",
                false,
                url,
                "Direct URL"
        );
    }

    private static JamendoTrack youtubeTrack(String url) {
        return new JamendoTrack(
                url.hashCode(),
                "YouTube audio",
                "YouTube",
                "",
                0,
                "",
                url,
                "mp3",
                "",
                true,
                url,
                "YouTube"
        );
    }

    private List<JamendoTrack> parseTracks(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        List<JamendoTrack> tracks = new ArrayList<>();
        JsonArray results = root.has("results") ? root.getAsJsonArray("results") : new JsonArray();
        for (JsonElement element : results) {
            JsonObject object = element.getAsJsonObject();
            tracks.add(new JamendoTrack(
                    getLong(object, "id"),
                    getString(object, "name", "Unknown track"),
                    getString(object, "artist_name", "Unknown artist"),
                    getString(object, "album_name", ""),
                    getInt(object, "duration"),
                    getString(object, "image", getString(object, "album_image", "")),
                    getString(object, "audio", ""),
                    "ogg",
                    getString(object, "license_ccurl", ""),
                    getBoolean(object, "audiodownload_allowed", false),
                    getString(object, "shorturl", ""),
                    "Jamendo"
            ));
        }
        return tracks;
    }

    private CompletableFuture<InputStream> openHttpStream(String url, String accept) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", accept)
                .GET()
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(response -> {
                    if (response.statusCode() / 100 != 2) {
                        try {
                            response.body().close();
                        } catch (Exception ignored) {
                        }
                        throw new RuntimeException("Audio HTTP " + response.statusCode());
                    }
                    return response.body();
                });
    }

    private HttpRequest.Builder authorizedRequest(String url, String token) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("Authorization", "OAuth " + token);
    }

    private boolean isSoundCloudConfigured() {
        return !soundCloudClientId.isBlank() && !soundCloudClientSecret.isBlank();
    }

    private static boolean isSoundCloudUrl(String value) {
        return value.startsWith("https://soundcloud.com/")
                || value.startsWith("http://soundcloud.com/")
                || value.startsWith("https://on.soundcloud.com/")
                || value.startsWith("http://on.soundcloud.com/");
    }

    private static boolean isYouTubeTrack(JamendoTrack track) {
        return track != null
                && (isYouTubeUrl(track.audioUrl())
                || (track.source() != null && track.source().toLowerCase().contains("youtube")));
    }

    private static boolean isYouTubeUrl(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase();
            return host.equals("youtube.com")
                    || host.endsWith(".youtube.com")
                    || host.equals("youtu.be")
                    || host.endsWith(".youtu.be");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String supportedFormat(String first, String second) {
        String value = (first + " " + second).toLowerCase();
        if (value.contains("ogg") || value.endsWith(".oga")) return "ogg";
        if (value.contains("mp3") || value.contains("mpeg audio") || value.endsWith(".mpga")) return "mp3";
        return "";
    }

    private static String inferFormat(String url) {
        return supportedFormat(url, url);
    }

    private static List<JamendoTrack> deduplicate(List<JamendoTrack> tracks) {
        List<JamendoTrack> unique = new ArrayList<>(tracks.size());
        Set<String> urls = new HashSet<>();
        for (JamendoTrack track : tracks) {
            if (track.audioUrl() != null && urls.add(track.audioUrl())) unique.add(track);
        }
        return unique;
    }

    private static void requireSuccess(int statusCode, String source) {
        if (statusCode / 100 != 2) throw new RuntimeException(source + " HTTP " + statusCode);
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodePath(String value) {
        String[] segments = value.split("/", -1);
        StringBuilder encoded = new StringBuilder();
        for (String segment : segments) {
            if (encoded.length() > 0) encoded.append('/');
            encoded.append(encodePathSegment(segment));
        }
        return encoded.toString();
    }

    private static String getString(JsonObject object, String key, String fallback) {
        if (!object.has(key) || object.get(key).isJsonNull()) return fallback;
        JsonElement value = object.get(key);
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            return array.isEmpty() ? fallback : array.get(0).getAsString();
        }
        return value.getAsString();
    }

    private static int getInt(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) return 0;
        try {
            return object.get(key).getAsInt();
        } catch (RuntimeException ignored) {
            try {
                return (int) Float.parseFloat(object.get(key).getAsString());
            } catch (RuntimeException ignoredAgain) {
                return 0;
            }
        }
    }

    private static double getDouble(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) return 0;
        try {
            return object.get(key).getAsDouble();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static int getMillisAsSeconds(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) return 0;
        try {
            return Math.max(0, (int) Math.round(object.get(key).getAsDouble() / 1000.0));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static long getLong(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) return 0L;
        try {
            return object.get(key).getAsLong();
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static boolean getBoolean(JsonObject object, String key, boolean fallback) {
        return object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsBoolean()
                : fallback;
    }
}
