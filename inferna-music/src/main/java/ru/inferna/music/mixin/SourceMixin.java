package ru.inferna.music.mixin;

import net.minecraft.client.sound.AudioStream;
import net.minecraft.client.sound.Source;
import org.lwjgl.openal.AL10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.inferna.music.audio.PlaybackPositionStream;

@Mixin(Source.class)
public abstract class SourceMixin {
    @Shadow
    private int pointer;

    @Shadow
    private AudioStream stream;

    @Inject(method = "removeProcessedBuffers", at = @At("RETURN"))
    private void infernaMusic$updatePlaybackPosition(CallbackInfoReturnable<Integer> callback) {
        if (!(stream instanceof PlaybackPositionStream positionStream)) return;

        positionStream.onBuffersProcessed(callback.getReturnValue());
        int sampleOffset = AL10.alGetSourcei(pointer, 0x1025);
        positionStream.updateSampleOffset(sampleOffset);
    }
}
