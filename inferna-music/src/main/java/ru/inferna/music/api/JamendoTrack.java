package ru.inferna.music.api;

public record JamendoTrack(
        long id,
        String name,
        String artist,
        String album,
        int duration,
        String imageUrl,
        String audioUrl,
        String audioFormat,
        String licenseUrl,
        boolean audioDownloadAllowed,
        String shortUrl,
        String source
) {
    public String subtitle() {
        if (album == null || album.isBlank()) return artist;
        return artist + "  ·  " + album;
    }

    public String sourceLabel() {
        return source == null || source.isBlank() ? "Open audio" : source;
    }

    public boolean isPlayable() {
        return "ogg".equalsIgnoreCase(audioFormat) || "mp3".equalsIgnoreCase(audioFormat);
    }
}
