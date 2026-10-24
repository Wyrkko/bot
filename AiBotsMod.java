package dev.aibots;

import dev.aibots.bot.BotManager;
import dev.aibots.command.BotCommand;
import dev.aibots.config.BotConfig;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class AiBotsMod implements ModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("aibots");

    private static BotConfig config;
    private static Path configFile;
    private static BotManager manager;

    public static BotConfig config() {
        return config;
    }

    public static BotManager manager() {
        return manager;
    }

    public static void saveConfig() {
        config.save(configFile);
    }

    @Override
    public void onInitialize() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("aibots");
        configFile = dir.resolve("config.json");
        config = BotConfig.load(configFile);
        manager = new BotManager(dir);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                BotCommand.register(dispatcher));
        ServerTickEvents.END_SERVER_TICK.register(server -> manager.tick(server));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> manager.saveIfDirty());

        LOG.info("AI Bots loaded. Use /bot help");
    }
}
