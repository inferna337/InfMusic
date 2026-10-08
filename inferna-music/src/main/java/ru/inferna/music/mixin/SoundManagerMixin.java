package ru.inferna.music.mixin;

import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.client.sound.SoundSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import ru.inferna.music.audio.SoundManagerAccess;
import ru.inferna.music.audio.SoundSystemAccess;

@Mixin(SoundManager.class)
public abstract class SoundManagerMixin implements SoundManagerAccess {
    @Shadow
    private SoundSystem soundSystem;

    @Override
    public void infernaMusic$pause(SoundInstance sound) {
        ((SoundSystemAccess) soundSystem).infernaMusic$pause(sound);
    }

    @Override
    public void infernaMusic$resume(SoundInstance sound) {
        ((SoundSystemAccess) soundSystem).infernaMusic$resume(sound);
    }
}
