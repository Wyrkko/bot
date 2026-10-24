package dev.aibots.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.aibots.AiBotsMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Saved to config/aibots/config.json. Change values in-game with /bot settings. */
public class BotConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** true = a bot that dies leaves the server; false = it respawns. */
    public boolean botLeaveOnDeath = true;
    /** Hard cap on simultaneously active bots. */
    public int maxBots = 3000;
    /** How many bots /bot massspawn creates per second (each one triggers a Mojang profile lookup). */
    public double massSpawnPerSecond = 2.0;
    /** Mass-spawned bots are scattered within this many blocks of the command source. */
    public int massSpawnRadius = 24;
    /** If a name is not a real account, still spawn it (with a default skin). */
    public boolean allowOfflineBots = true;

    public static BotConfig load(Path file) {
        try {
            if (Files.exists(file)) {
                BotConfig c = GSON.fromJson(Files.readString(file), BotConfig.class);
                if (c != null) {
                    c.save(file);
                    return c;
                }
            }
        } catch (Exception e) {
            AiBotsMod.LOG.error("Could not read config, using defaults", e);
        }
        BotConfig c = new BotConfig();
        c.save(file);
        return c;
    }

    public void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this));
        } catch (IOException e) {
            AiBotsMod.LOG.error("Could not save config", e);
        }
    }
}
