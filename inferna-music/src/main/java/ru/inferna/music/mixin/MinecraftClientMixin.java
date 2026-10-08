package ru.inferna.music.mixin;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import ru.inferna.music.InfernaMusicClient;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    /**
     * Keep the SoundManager alive while Minecraft swaps the client world.
     * This preserves custom streams and their current playback position.
     */
    @ModifyVariable(
            method = "setWorld(Lnet/minecraft/client/world/ClientWorld;Z)V",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private boolean infernaMusic$keepSoundsDuringWorldChange(boolean stopSounds) {
        return stopSounds
                && (InfernaMusicClient.PLAYER == null || !InfernaMusicClient.PLAYER.hasActiveTrack());
    }
}
