# Inferna Music

Клиентский онлайн-плеер музыки для Fabric 1.21.11.

Полная документация находится в [inferna-music/README.md](inferna-music/README.md).

## Кратко

- Открытие плеера клавишей `O`.
- Поиск YouTube, Jamendo, Internet Archive, Openverse и Openwhyd.
- Опциональная поддержка SoundCloud.
- Прямые HTTP(S)-ссылки на MP3/OGG.
- Воспроизведение, пауза, остановка, перемотка, громкость, плейлисты и лайки.
- Встроенные `yt-dlp` и FFmpeg для Windows x86_64 и Linux x86_64.
- Minecraft `1.21.11`, Fabric Loader `0.19.5+`, Fabric API и Java 21+.

Встроенные программы извлекаются в `.minecraft/config/inferna_music/bin/` при первом воспроизведении YouTube.

## Сборка

```bash
cd inferna-music
./gradlew build
```

JAR создаётся в `inferna-music/build/libs/`.
