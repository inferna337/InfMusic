# InfMusic

Client-side online music player for Fabric 1.21.11.

## English

- Press `O` to open the player.
- Search supported catalogs and play direct OGG/MP3 streams.
- Play, pause, stop, seek, change volume, skip tracks, and manage liked tracks.
- Audio continues while the GUI is closed.
- Uses Minecraft's native GUI rendering through `DrawContext`.
- Playback and network loading run asynchronously.

### Requirements

- Minecraft `1.21.11`
- Fabric Loader and Fabric API
- Java 21
- YouTube helper binaries are bundled for Windows and Linux x86_64; no separate
  Python, `yt-dlp`, or `ffmpeg` installation is required on those platforms.
- Bundled helper licenses and sources are listed in `tools/THIRD_PARTY_NOTICES.txt`.
- Optional Jamendo and SoundCloud API credentials in
  `.minecraft/config/inferna_music.json`

### Build

```bash
gradle build
```

The remapped JAR is written to `build/libs/`.

## Русский

Клиентский онлайн-плеер музыки для Fabric 1.21.11.

- Нажмите `O`, чтобы открыть плеер.
- Ищите музыку в поддерживаемых каталогах и запускайте прямые OGG/MP3-потоки.
- Доступны воспроизведение, пауза, остановка, перемотка, громкость, переключение треков и лайки.
- Музыка продолжает играть после закрытия GUI.
- Интерфейс использует обычный рендер Minecraft через `DrawContext`.
- Сеть и загрузка аудио работают асинхронно.

### Требования

- Minecraft `1.21.11`
- Fabric Loader и Fabric API
- Java 21
- `yt-dlp` и `ffmpeg` для аудио с YouTube
- Необязательные ключи Jamendo и SoundCloud в
  `.minecraft/config/inferna_music.json`

### Сборка

```bash
gradle build
```

Готовый remapped JAR находится в `build/libs/`.
