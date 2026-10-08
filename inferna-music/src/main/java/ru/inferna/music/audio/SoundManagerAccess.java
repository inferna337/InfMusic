package ru.inferna.music.audio;

import net.minecraft.client.sound.SoundInstance;

public interface SoundManagerAccess {
    void infernaMusic$pause(SoundInstance sound);

    void infernaMusic$resume(SoundInstance sound);
}
