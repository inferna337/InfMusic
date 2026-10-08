package ru.inferna.music.audio;

import net.minecraft.client.sound.SoundInstance;

public interface SoundSystemAccess {
    void infernaMusic$pause(SoundInstance sound);

    void infernaMusic$resume(SoundInstance sound);
}
