package dev.aibots.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.aibots.AiBotsMod;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;

/** Tracks bot identities (difficulty, faction), spawns bots and runs mass-spawn jobs. */
public class BotManager {
    /** Per-bot saved data. Difficulty and faction are stored now; the AI that uses them comes in later stages. */
    public static class BotRecord {
        public int difficulty = 5;
        public String faction = null;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path recordsFile;
    private final Map<String, BotRecord> records = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private boolean dirty = false;

    public final NameProvider names = new NameProvider();
    public final KitManager kits;
    private final Random random = new Random();

    private MassJob job;

    private static final class MassJob {
        ServerLevel level;
        Vec3 center;
        String kit;
        int remaining;
        int requested;
        int started;
        int failed;
        double budget;
        UUID issuer;
    }

    public BotManager(Path dir) {
        this.recordsFile = dir.resolve("bots.json");
        this.kits = new KitManager(dir.resolve("kits"));
        names.load(dir.resolve("names.txt"));
        loadRecords();
    }

    // ---------- records ----------

    private void loadRecords() {
        try {
            if (Files.exists(recordsFile)) {
                Type t = new TypeToken<LinkedHashMap<String, BotRecord>>() {}.getType();
                Map<String, BotRecord> m = GSON.fromJson(Files.readString(recordsFile), t);
                if (m != null) records.putAll(m);
            }
        } catch (Exception e) {
            AiBotsMod.LOG.error("Could not read bots.json", e);
        }
    }

    public void saveIfDirty() {
        if (!dirty) return;
        dirty = false;
        try {
            Files.createDirectories(recordsFile.getParent());
            Files.writeString(recordsFile, GSON.toJson(records));
        } catch (IOException e) {
            AiBotsMod.LOG.error("Could not save bots.json", e);
        }
    }

    public BotRecord record(String name) {
        return records.computeIfAbsent(name, k -> {
            dirty = true;
            return new BotRecord();
        });
    }

    public void markDirty() {
        dirty = true;
    }

    public Map<String, BotRecord> allRecords() {
        return records;
    }

    // ---------- queries ----------

    public List<BotPlayer> activeBots(MinecraftServer server) {
        List<BotPlayer> out = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof BotPlayer b) out.add(b);
        }
        return out;
    }

    public List<String> botNames(MinecraftServer server) {
        List<String> out = new ArrayList<>();
        for (BotPlayer b : activeBots(server)) out.add(b.getGameProfile().name());
        return out;
    }

    public BotPlayer find(MinecraftServer server, String name) {
        ServerPlayer p = server.getPlayerList().getPlayerByName(name);
        return p instanceof BotPlayer b ? b : null;
    }

    public boolean nameTaken(MinecraftServer server, String name) {
        return server.getPlayerList().getPlayerByName(name) != null || BotPlayer.isSpawning(name);
    }

    public int activeOrPending(MinecraftServer server) {
        return activeBots(server).size() + BotPlayer.spawningCount();
    }

    // ---------- spawning ----------

    /** Returns null on success, otherwise a human-readable failure reason. */
    public String spawn(MinecraftServer server, String name, ServerLevel level, Vec3 pos, double yaw, double pitch, String kit) {
        if (!NameProvider.VALID.matcher(name).matches()) {
            return "Invalid username (3-16 characters: letters, digits, underscore).";
        }
        if (nameTaken(server, name)) {
            return name + " is already online or being spawned.";
        }
        if (activeOrPending(server) >= AiBotsMod.config().maxBots) {
            return "Bot limit reached (" + AiBotsMod.config().maxBots + "). Change it with /bot settings maxbots <n>.";
        }
        if (!Level.isInSpawnableBounds(BlockPos.containing(pos))) {
            return "Cannot place a bot outside the world.";
        }
        BotRecord rec = record(name);
        boolean started = BotPlayer.create(server, name, level, pos, yaw, pitch, GameType.SURVIVAL, bot -> {
            markDirty();
            if (kit != null) kits.load(kit, bot);
            if (rec.faction != null) {
                // Faction behaviour arrives in a later stage; membership is already stored.
            }
        });
        if (!started) {
            return name + " is not a real account and offline bots are disabled (/bot settings allowofflinebots true).";
        }
        return null;
    }

    // ---------- mass spawn ----------

    public boolean massSpawnRunning() {
        return job != null;
    }

    public int massSpawnRemaining() {
        return job == null ? 0 : job.remaining;
    }

    public void startMassSpawn(ServerLevel level, Vec3 center, int count, String kit, UUID issuer) {
        MassJob j = new MassJob();
        j.level = level;
        j.center = center;
        j.kit = kit;
        j.remaining = count;
        j.requested = count;
        j.issuer = issuer;
        this.job = j;
    }

    public void cancelMassSpawn(MinecraftServer server) {
        if (job != null) {
            finishJob(server, true);
        }
    }

    /** Called every server tick. */
    public void tick(MinecraftServer server) {
        if (server.getTickCount() % 200 == 0) saveIfDirty();
        if (job == null) return;

        job.budget += Math.max(0.05, AiBotsMod.config().massSpawnPerSecond) / 20.0;
        while (job.budget >= 1.0 && job.remaining > 0) {
            job.budget -= 1.0;
            if (activeOrPending(server) >= AiBotsMod.config().maxBots) {
                job.remaining = 0;
                break;
            }
            job.remaining--;
            if (spawnOneForJob(server)) job.started++;
            else job.failed++;
        }
        if (job.remaining <= 0) {
            finishJob(server, false);
        }
    }

    private boolean spawnOneForJob(MinecraftServer server) {
        for (int attempt = 0; attempt < 5; attempt++) {
            String name = names.next(n -> nameTaken(server, n));
            Vec3 pos = scatter(job);
            String err = spawn(server, name, job.level, pos, random.nextInt(360) - 180, 0, job.kit);
            if (err == null) return true;
        }
        return false;
    }

    private Vec3 scatter(MassJob j) {
        int r = Math.max(1, AiBotsMod.config().massSpawnRadius);
        double angle = random.nextDouble() * Math.PI * 2;
        double dist = Math.sqrt(random.nextDouble()) * r;
        double x = j.center.x + Math.cos(angle) * dist;
        double z = j.center.z + Math.sin(angle) * dist;
        double y = j.center.y;
        if (!j.level.dimensionType().hasCeiling()) {
            y = j.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
        }
        return new Vec3(x, y, z);
    }

    private void finishJob(MinecraftServer server, boolean cancelled) {
        MassJob j = job;
        job = null;
        String msg = (cancelled ? "Mass spawn cancelled: " : "Mass spawn finished: ")
                + j.started + " of " + j.requested + " bots requested"
                + (j.failed > 0 ? ", " + j.failed + " failed (see log)" : "") + ".";
        AiBotsMod.LOG.info(msg);
        if (j.issuer != null) {
            ServerPlayer p = server.getPlayerList().getPlayer(j.issuer);
            if (p != null) p.sendSystemMessage(Component.literal(msg));
        }
    }
}
