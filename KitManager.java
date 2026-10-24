package dev.aibots.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.aibots.AiBotsMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryOps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Pre-saved inventories. Stored as JSON in config/aibots/kits/&lt;name&gt;.json. */
public class KitManager {
    public static final Pattern VALID = Pattern.compile("[a-z0-9_\\-]{1,32}");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path dir;

    public KitManager(Path dir) {
        this.dir = dir;
    }

    private Path file(String name) {
        return dir.resolve(name + ".json");
    }

    public static String normalise(String name) {
        return name.toLowerCase();
    }

    public boolean exists(String name) {
        return Files.exists(file(normalise(name)));
    }

    public List<String> list() {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(dir)) return names;
        try (Stream<Path> s = Files.list(dir)) {
            s.forEach(p -> {
                String f = p.getFileName().toString();
                if (f.endsWith(".json")) names.add(f.substring(0, f.length() - 5));
            });
        } catch (IOException e) {
            AiBotsMod.LOG.error("Could not list kits", e);
        }
        names.sort(String::compareTo);
        return names;
    }

    /** Saves every inventory slot (main, armour, offhand) of the given player as a kit. Returns item count or -1 on failure. */
    public int save(String rawName, ServerPlayer from) {
        String name = normalise(rawName);
        RegistryOps<JsonElement> ops = from.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        Inventory inv = from.getInventory();
        JsonObject slots = new JsonObject();
        int count = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            Optional<JsonElement> json = ItemStack.CODEC.encodeStart(ops, stack).result();
            if (json.isPresent()) {
                slots.add(Integer.toString(i), json.get());
                count++;
            }
        }
        JsonObject root = new JsonObject();
        root.add("slots", slots);
        try {
            Files.createDirectories(dir);
            Files.writeString(file(name), GSON.toJson(root));
            return count;
        } catch (IOException e) {
            AiBotsMod.LOG.error("Could not save kit " + name, e);
            return -1;
        }
    }

    /** Replaces the bot inventory with the kit. Returns item count, or -1 if the kit is missing/unreadable. */
    public int load(String rawName, ServerPlayer bot) {
        String name = normalise(rawName);
        Path f = file(name);
        if (!Files.exists(f)) return -1;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            JsonObject slots = root.getAsJsonObject("slots");
            RegistryAccess access = bot.registryAccess();
            RegistryOps<JsonElement> ops = access.createSerializationContext(JsonOps.INSTANCE);
            Inventory inv = bot.getInventory();
            inv.clearContent();
            int count = 0;
            for (String key : slots.keySet()) {
                int slot = Integer.parseInt(key);
                if (slot < 0 || slot >= inv.getContainerSize()) continue;
                Optional<ItemStack> stack = ItemStack.CODEC.parse(ops, slots.get(key)).result();
                if (stack.isPresent()) {
                    inv.setItem(slot, stack.get());
                    count++;
                }
            }
            bot.inventoryMenu.broadcastChanges();
            return count;
        } catch (Exception e) {
            AiBotsMod.LOG.error("Could not load kit " + name, e);
            return -1;
        }
    }

    public boolean delete(String rawName) {
        try {
            return Files.deleteIfExists(file(normalise(rawName)));
        } catch (IOException e) {
            return false;
        }
    }
}
