# Inferna Music

Client-side online music player for Fabric 1.21.11.

## English

Press `O` to open the player. Search by track, artist, album, or name, then play results from YouTube, Jamendo, Internet Archive, Openverse, or Openwhyd. Optional SoundCloud search and playback is available with SoundCloud credentials. Direct HTTP(S) audio URLs are also supported.

The player supports MP3 and OGG playback, play/pause/resume/stop, seeking, volume, previous/next track, playlists, liked tracks, continued playback after closing the GUI, and asynchronous network loading.

### Requirements

- Minecraft `1.21.11`
- Fabric Loader `0.19.5+`
- Fabric API
- Java 21+

YouTube support bundles `yt-dlp` and FFmpeg for Windows x86_64 and Linux x86_64. No separate Python, yt-dlp, or FFmpeg installation is required on those platforms. The helpers are extracted to `.minecraft/config/inferna_music/bin/` when YouTube playback first needs them. Other operating systems and CPU architectures require compatible executables in `PATH` or configured paths.

### Configuration

The configuration file is created at `.minecraft/config/inferna_music.json`:

```json
{
  "clientId": "YOUR_JAMENDO_CLIENT_ID",
  "soundCloudClientId": "",
  "soundCloudClientSecret": "",
  "ytDlpPath": "yt-dlp",
  "ffmpegPath": "ffmpeg",
  "volume": 0.85
}
```

Jamendo and SoundCloud credentials are optional. YouTube, Internet Archive, Openverse, Openwhyd, and direct URLs do not use those credentials. Bundled helper licenses and source information are in `src/main/resources/tools/THIRD_PARTY_NOTICES.txt`.

### Build

```bash
./gradlew build
```

The remapped JAR is written to `build/libs/`.

## Русский

Клиентский онлайн-плеер музыки для Fabric 1.21.11.

Нажмите `O`, чтобы открыть плеер. Поиск выполняется по YouTube, Jamendo, Internet Archive, Openverse и Openwhyd. SoundCloud поддерживается при указании его ключей. Также можно открывать прямые HTTP(S)-ссылки на аудио.

Доступны форматы MP3 и OGG, воспроизведение, пауза, продолжение, остановка, перемотка, громкость, предыдущий/следующий трек, плейлисты, лайки и продолжение музыки после закрытия GUI. Сеть и декодирование работают асинхронно.

### Требования

- Minecraft `1.21.11`
- Fabric Loader `0.19.5+`
- Fabric API
- Java 21+

Для YouTube в мод встроены `yt-dlp` и FFmpeg для Windows x86_64 и Linux x86_64. Отдельно устанавливать Python, yt-dlp или FFmpeg на этих платформах не нужно. Вспомогательные программы извлекаются в `.minecraft/config/inferna_music/bin/` при первом воспроизведении YouTube. На других системах и архитектурах нужны совместимые программы в `PATH` или пути к ним в конфигурации.

### Конфигурация

Файл создаётся по пути `.minecraft/config/inferna_music.json`:

```json
{
  "clientId": "YOUR_JAMENDO_CLIENT_ID",
  "soundCloudClientId": "",
  "soundCloudClientSecret": "",
  "ytDlpPath": "yt-dlp",
  "ffmpegPath": "ffmpeg",
  "volume": 0.85
}
```

Ключи Jamendo и SoundCloud необязательны. YouTube, Internet Archive, Openverse, Openwhyd и прямые ссылки их не используют. Лицензии и сведения об исходниках встроенных программ находятся в `src/main/resources/tools/THIRD_PARTY_NOTICES.txt`.

### Сборка

```bash
./gradlew build
```

Готовый remapped JAR находится в `build/libs/`.
