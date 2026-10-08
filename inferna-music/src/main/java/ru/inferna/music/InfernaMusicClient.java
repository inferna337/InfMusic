package ru.inferna.music;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import ru.inferna.music.api.JamendoClient;
import ru.inferna.music.config.MusicConfig;
import ru.inferna.music.audio.MusicPlayer;
import ru.inferna.music.gui.MusicScreen;

import net.minecraft.util.Identifier;

public final class InfernaMusicClient implements ClientModInitializer {
    public static final String MOD_ID = "inferna_music";
    public static MusicConfig CONFIG;
    public static JamendoClient JAMENDO;
    public static MusicPlayer PLAYER;
    public static KeyBinding OPEN_KEY;

    @Override
    public void onInitializeClient() {
        CONFIG = MusicConfig.load();
        JAMENDO = new JamendoClient(
                CONFIG.isConfigured() ? CONFIG.clientId : "",
                CONFIG.soundCloudClientId,
                CONFIG.soundCloudClientSecret,
                CONFIG.ytDlpPath,
                CONFIG.ffmpegPath
        );
        PLAYER = new MusicPlayer(MinecraftClient.getInstance(), JAMENDO, CONFIG);
        OPEN_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.inferna_music.open",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                KeyBinding.Category.create(Identifier.of("inferna_music", "main"))
        ));

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            PLAYER.stop();
            client.getSoundManager().stopAll();
        });

        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            PLAYER.tick();
            while (OPEN_KEY.wasPressed()) {
                if (client.currentScreen instanceof MusicScreen) {
                    client.setScreen(null);
                } else {
                    client.setScreen(new MusicScreen(null));
                }
            }
        });
    }
}
