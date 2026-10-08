package ru.inferna.music.mixin;

import net.minecraft.client.sound.Channel;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import ru.inferna.music.audio.SoundSystemAccess;

import java.util.Map;

@Mixin(SoundSystem.class)
public abstract class SoundSystemMixin implements SoundSystemAccess {
    @Shadow
    private Map<SoundInstance, Channel.SourceManager> sources;

    @Override
    public void infernaMusic$pause(SoundInstance sound) {
        Channel.SourceManager sourceManager = sources.get(sound);
        if (sourceManager != null) {
            sourceManager.run(source -> source.pause());
        }
    }

    @Override
    public void infernaMusic$resume(SoundInstance sound) {
        Channel.SourceManager sourceManager = sources.get(sound);
        if (sourceManager != null) {
            sourceManager.run(source -> source.resume());
        }
    }
}
