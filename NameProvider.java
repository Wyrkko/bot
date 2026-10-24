package dev.aibots.bot;

import dev.aibots.AiBotsMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Supplies usernames for mass spawning.
 * 1) Names from config/aibots/names.txt (one real Minecraft username per line) are used first,
 *    in shuffled order, so those bots get their real skins.
 * 2) When the list runs out, plausible random names are generated. If such a name happens to be a
 *    real account it gets that account skin; otherwise it spawns with a default skin.
 */
public class NameProvider {
    public static final Pattern VALID = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private static final String[] A = {"Dark", "Swift", "Pixel", "Frost", "Ender", "Nova", "Shadow", "Blaze", "Iron", "Lunar",
            "Cobalt", "Rapid", "Silent", "Toxic", "Crimson", "Storm", "Ghost", "Alpha", "Omega", "Turbo", "Hyper", "Mystic",
            "Rusty", "Sunny", "Wild", "Cosmic", "Arctic", "Neon", "Royal", "Sly", "Bold", "Lucky", "Vex", "Kry", "Nuv", "Zen"};
    private static final String[] B = {"Wolf", "Fox", "Knight", "Miner", "Slayer", "Ranger", "Hunter", "Crafter", "Builder",
            "Raider", "Blade", "Bow", "Creeper", "Golem", "Pilot", "Wizard", "Ninja", "Viking", "Panda", "Tiger", "Falcon",
            "Dragon", "Spark", "Bolt", "Clutch", "Combo", "Reflex", "Strafe", "Zyx", "ora", "ix", "en"};

    private final Random random = new Random();
    private final List<String> fromFile = new ArrayList<>();
    private int next = 0;

    public void load(Path file) {
        fromFile.clear();
        next = 0;
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, "# One real Minecraft username per line (3-16 chars: letters, digits, underscore).\n"
                        + "# Mass-spawned bots use these first so they get real skins.\n");
                return;
            }
            for (String line : Files.readAllLines(file)) {
                String s = line.trim();
                if (!s.isEmpty() && !s.startsWith("#") && VALID.matcher(s).matches()) {
                    fromFile.add(s);
                }
            }
            Collections.shuffle(fromFile, random);
            AiBotsMod.LOG.info("Loaded {} bot usernames from {}", fromFile.size(), file.getFileName());
        } catch (IOException e) {
            AiBotsMod.LOG.error("Could not read names file", e);
        }
    }

    public int fileNameCount() {
        return fromFile.size();
    }

    /** Returns an unused name; taken.test(name) must return true if the name is already online/spawning. */
    public String next(Predicate<String> taken) {
        while (next < fromFile.size()) {
            String n = fromFile.get(next++);
            if (!taken.test(n)) return n;
        }
        for (int i = 0; i < 200; i++) {
            String n = generate();
            if (!taken.test(n)) return n;
        }
        return "Bot_" + (100000 + random.nextInt(900000));
    }

    private String generate() {
        String base = A[random.nextInt(A.length)] + B[random.nextInt(B.length)];
        int style = random.nextInt(10);
        String n;
        if (style < 4) n = base;
        else if (style < 7) n = base + (random.nextInt(99) + 1);
        else if (style < 9) n = base + "_" + (random.nextInt(999) + 1);
        else n = A[random.nextInt(A.length)] + "_" + B[random.nextInt(B.length)];
        return n.length() > 16 ? n.substring(0, 16) : n;
    }
}
