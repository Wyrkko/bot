package dev.aibots.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.aibots.AiBotsMod;
import dev.aibots.bot.BotManager;
import dev.aibots.bot.BotPlayer;
import dev.aibots.bot.KitManager;
import dev.aibots.config.BotConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec2;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class BotCommand {
    private BotCommand() {}

    private static BotManager mgr() {
        return AiBotsMod.manager();
    }

    private static BotConfig cfg() {
        return AiBotsMod.config();
    }

    // ---------- registration ----------

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = literal("bot");

        root.then(literal("help").executes(BotCommand::help));

        // /bot spawn <name> [kit]
        root.then(literal("spawn").requires(admin())
                .then(argument("name", StringArgumentType.word())
                        .executes(c -> spawn(c, null))
                        .then(argument("kit", StringArgumentType.word()).suggests(BotCommand::suggestKits)
                                .executes(c -> spawn(c, StringArgumentType.getString(c, "kit"))))));

        // /bot massspawn <count> [kit]   and   /bot massspawn cancel
        root.then(literal("massspawn").requires(admin())
                .then(literal("cancel").executes(BotCommand::massCancel))
                .then(argument("count", IntegerArgumentType.integer(1, 100000))
                        .executes(c -> massSpawn(c, null))
                        .then(argument("kit", StringArgumentType.word()).suggests(BotCommand::suggestKits)
                                .executes(c -> massSpawn(c, StringArgumentType.getString(c, "kit"))))));

        // /bot remove <name>, /bot removeall, /bot list
        root.then(literal("remove").requires(admin())
                .then(argument("name", StringArgumentType.word()).suggests(BotCommand::suggestBots)
                        .executes(BotCommand::remove)));
        root.then(literal("removeall").requires(admin()).executes(BotCommand::removeAll));
        root.then(literal("list").requires(admin()).executes(BotCommand::list));

        // /bot difficulty <name> <1-10>   (stored now, used by the combat AI in a later stage)
        root.then(literal("difficulty").requires(admin())
                .then(argument("name", StringArgumentType.word()).suggests(BotCommand::suggestBots)
                        .then(argument("level", IntegerArgumentType.integer(1, 10)).executes(BotCommand::difficulty))));

        // /bot faction join|leave|list   (stored now, behaviour in a later stage)
        root.then(literal("faction").requires(admin())
                .then(literal("join")
                        .then(argument("name", StringArgumentType.word()).suggests(BotCommand::suggestBots)
                                .then(argument("faction", StringArgumentType.word()).executes(BotCommand::factionJoin))))
                .then(literal("leave")
                        .then(argument("name", StringArgumentType.word()).suggests(BotCommand::suggestBots)
                                .executes(BotCommand::factionLeave)))
                .then(literal("list").executes(BotCommand::factionList)));

        // Kits: /bot kit create|load|loadall|list|delete   plus shortcuts /bot createkit and /bot loadkit
        root.then(literal("kit").requires(admin())
                .then(kitCreateNode("create"))
                .then(kitLoadNode("load"))
                .then(literal("loadall")
                        .then(argument("kit", StringArgumentType.word()).suggests(BotCommand::suggestKits)
                                .executes(BotCommand::kitLoadAll)))
                .then(literal("list").executes(BotCommand::kitList))
                .then(literal("delete")
                        .then(argument("kit", StringArgumentType.word()).suggests(BotCommand::suggestKits)
                                .executes(BotCommand::kitDelete))));
        root.then(kitCreateNode("createkit").requires(admin()));
        root.then(kitLoadNode("loadkit").requires(admin()));

        // /bot settings ...
        root.then(buildSettings());

        dispatcher.register(root);
    }

    private static java.util.function.Predicate<CommandSourceStack> admin() {
        return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> kitCreateNode(String literalName) {
        return literal(literalName)
                .then(argument("kit", StringArgumentType.word()).executes(BotCommand::kitCreate));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> kitLoadNode(String literalName) {
        return literal(literalName)
                .then(argument("name", StringArgumentType.word()).suggests(BotCommand::suggestBots)
                        .then(argument("kit", StringArgumentType.word()).suggests(BotCommand::suggestKits)
                                .executes(BotCommand::kitLoad)));
    }

    // ---------- settings ----------

    private static LiteralArgumentBuilder<CommandSourceStack> buildSettings() {
        LiteralArgumentBuilder<CommandSourceStack> s = literal("settings").requires(admin())
                .executes(BotCommand::showSettings);

        s.then(literal("botleaveondeath")
                .executes(c -> reply(c, "botleaveondeath = " + cfg().botLeaveOnDeath
                        + " (true: a bot that dies leaves the server; false: it respawns)"))
                .then(argument("value", BoolArgumentType.bool()).executes(c -> {
                    cfg().botLeaveOnDeath = BoolArgumentType.getBool(c, "value");
                    AiBotsMod.saveConfig();
                    return reply(c, "botleaveondeath set to " + cfg().botLeaveOnDeath
                            + (cfg().botLeaveOnDeath ? " - bots now leave when they die." : " - bots now respawn when they die."));
                })));

        s.then(literal("allowofflinebots")
                .executes(c -> reply(c, "allowofflinebots = " + cfg().allowOfflineBots))
                .then(argument("value", BoolArgumentType.bool()).executes(c -> {
                    cfg().allowOfflineBots = BoolArgumentType.getBool(c, "value");
                    AiBotsMod.saveConfig();
                    return reply(c, "allowofflinebots set to " + cfg().allowOfflineBots);
                })));

        s.then(literal("maxbots")
                .executes(c -> reply(c, "maxbots = " + cfg().maxBots))
                .then(argument("value", IntegerArgumentType.integer(1, 100000)).executes(c -> {
                    cfg().maxBots = IntegerArgumentType.getInteger(c, "value");
                    AiBotsMod.saveConfig();
                    return reply(c, "maxbots set to " + cfg().maxBots);
                })));

        s.then(literal("masspawnrate")
                .executes(c -> reply(c, "masspawnrate = " + cfg().massSpawnPerSecond + " bots/second"))
                .then(argument("value", DoubleArgumentType.doubleArg(0.1, 50.0)).executes(c -> {
                    cfg().massSpawnPerSecond = DoubleArgumentType.getDouble(c, "value");
                    AiBotsMod.saveConfig();
                    return reply(c, "masspawnrate set to " + cfg().massSpawnPerSecond + " bots/second");
                })));

        s.then(literal("masspawnradius")
                .executes(c -> reply(c, "masspawnradius = " + cfg().massSpawnRadius + " blocks"))
                .then(argument("value", IntegerArgumentType.integer(1, 512)).executes(c -> {
                    cfg().massSpawnRadius = IntegerArgumentType.getInteger(c, "value");
                    AiBotsMod.saveConfig();
                    return reply(c, "masspawnradius set to " + cfg().massSpawnRadius);
                })));
        return s;
    }

    private static int showSettings(CommandContext<CommandSourceStack> c) {
        BotConfig k = cfg();
        reply(c, "Bot settings (change with /bot settings <name> <value>):");
        reply(c, " botleaveondeath = " + k.botLeaveOnDeath);
        reply(c, " allowofflinebots = " + k.allowOfflineBots);
        reply(c, " maxbots = " + k.maxBots);
        reply(c, " masspawnrate = " + k.massSpawnPerSecond + " per second");
        reply(c, " masspawnradius = " + k.massSpawnRadius + " blocks");
        return 1;
    }

    // ---------- suggestions ----------

    private static CompletableFuture<Suggestions> suggestBots(CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
        return SharedSuggestionProvider.suggest(mgr().botNames(c.getSource().getServer()), b);
    }

    private static CompletableFuture<Suggestions> suggestKits(CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
        return SharedSuggestionProvider.suggest(mgr().kits.list(), b);
    }

    // ---------- helpers ----------

    private static int reply(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int fail(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendFailure(Component.literal(msg));
        return 0;
    }

    // ---------- commands ----------

    private static int help(CommandContext<CommandSourceStack> c) {
        String[] lines = {
                "/bot spawn <name> [kit] - spawn a bot using a real username (real skin)",
                "/bot massspawn <count> [kit] - spawn many bots (names from config/aibots/names.txt, then random)",
                "/bot massspawn cancel - stop a running mass spawn",
                "/bot remove <name> | removeall | list",
                "/bot difficulty <name> <1-10> - stored; combat AI comes in a later stage",
                "/bot faction join <name> <faction> | leave <name> | list - stored; behaviour comes later",
                "/bot kit create <kit> - save YOUR inventory as a kit (also /bot createkit <kit>)",
                "/bot kit load <name> <kit> - give a bot a kit (also /bot loadkit <name> <kit>)",
                "/bot kit loadall <kit> | list | delete <kit>",
                "/bot settings [botleaveondeath|allowofflinebots|maxbots|masspawnrate|masspawnradius] [value]"
        };
        for (String l : lines) reply(c, l);
        return 1;
    }

    private static int spawn(CommandContext<CommandSourceStack> c, String kit) {
        CommandSourceStack src = c.getSource();
        String name = StringArgumentType.getString(c, "name");
        if (kit != null && !mgr().kits.exists(kit)) return fail(c, "No kit named '" + kit + "'. See /bot kit list");
        Vec2 rot = src.getRotation();
        String err = mgr().spawn(src.getServer(), name, src.getLevel(), src.getPosition(), rot.y, rot.x, kit);
        if (err != null) return fail(c, err);
        return reply(c, "Spawning bot " + name + "...");
    }

    private static int massSpawn(CommandContext<CommandSourceStack> c, String kit) {
        CommandSourceStack src = c.getSource();
        MinecraftServer server = src.getServer();
        int count = IntegerArgumentType.getInteger(c, "count");
        if (mgr().massSpawnRunning()) {
            return fail(c, "A mass spawn is already running (" + mgr().massSpawnRemaining() + " left). Use /bot massspawn cancel.");
        }
        if (kit != null && !mgr().kits.exists(kit)) return fail(c, "No kit named '" + kit + "'. See /bot kit list");
        int room = cfg().maxBots - mgr().activeOrPending(server);
        if (room <= 0) return fail(c, "Bot limit reached (" + cfg().maxBots + ").");
        int actual = Math.min(count, room);
        UUID issuer = src.getEntity() instanceof ServerPlayer p ? p.getUUID() : null;
        mgr().startMassSpawn(src.getLevel(), src.getPosition(), actual, kit, issuer);
        double secs = actual / Math.max(0.1, cfg().massSpawnPerSecond);
        reply(c, "Mass spawning " + actual + " bots at " + cfg().massSpawnPerSecond + "/second (about "
                + Math.round(secs) + "s). " + mgr().names.fileNameCount() + " names loaded from names.txt; the rest are random.");
        if (actual < count) reply(c, "(Limited by maxbots = " + cfg().maxBots + ")");
        if (actual > 200) reply(c, "Warning: hundreds of bots will heavily load the server. Watch TPS and use /bot massspawn cancel if needed.");
        return 1;
    }

    private static int massCancel(CommandContext<CommandSourceStack> c) {
        if (!mgr().massSpawnRunning()) return fail(c, "No mass spawn is running.");
        mgr().cancelMassSpawn(c.getSource().getServer());
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "name");
        BotPlayer bot = mgr().find(c.getSource().getServer(), name);
        if (bot == null) return fail(c, "No active bot named " + name);
        bot.kill(Component.literal("Removed"));
        return reply(c, "Removed bot " + name);
    }

    private static int removeAll(CommandContext<CommandSourceStack> c) {
        mgr().cancelMassSpawn(c.getSource().getServer());
        List<BotPlayer> bots = mgr().activeBots(c.getSource().getServer());
        for (BotPlayer b : bots) b.kill(Component.literal("Removed"));
        return reply(c, "Removed " + bots.size() + " bots.");
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        List<String> names = mgr().botNames(c.getSource().getServer());
        reply(c, "Active bots: " + names.size()
                + (BotPlayer.spawningCount() > 0 ? " (+" + BotPlayer.spawningCount() + " spawning)" : ""));
        if (!names.isEmpty()) {
            List<String> shown = names.size() > 50 ? names.subList(0, 50) : names;
            reply(c, String.join(", ", shown) + (names.size() > 50 ? ", ... (" + (names.size() - 50) + " more)" : ""));
        }
        return names.size();
    }

    private static int difficulty(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "name");
        int level = IntegerArgumentType.getInteger(c, "level");
        if (mgr().find(c.getSource().getServer(), name) == null) return fail(c, "No active bot named " + name);
        mgr().record(name).difficulty = level;
        mgr().markDirty();
        return reply(c, name + " difficulty set to " + level + " (stored; the combat AI that uses it is not built yet).");
    }

    private static int factionJoin(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "name");
        String faction = StringArgumentType.getString(c, "faction");
        if (mgr().find(c.getSource().getServer(), name) == null) return fail(c, "No active bot named " + name);
        mgr().record(name).faction = faction;
        mgr().markDirty();
        return reply(c, name + " joined faction " + faction + " (stored; faction behaviour is not built yet).");
    }

    private static int factionLeave(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "name");
        if (mgr().find(c.getSource().getServer(), name) == null) return fail(c, "No active bot named " + name);
        mgr().record(name).faction = null;
        mgr().markDirty();
        return reply(c, name + " left its faction.");
    }

    private static int factionList(CommandContext<CommandSourceStack> c) {
        Map<String, List<String>> byFaction = new TreeMap<>();
        for (Map.Entry<String, BotManager.BotRecord> e : mgr().allRecords().entrySet()) {
            String f = e.getValue().faction;
            if (f != null) byFaction.computeIfAbsent(f, k -> new ArrayList<>()).add(e.getKey());
        }
        if (byFaction.isEmpty()) return reply(c, "No factions yet.");
        for (Map.Entry<String, List<String>> e : byFaction.entrySet()) {
            List<String> m = e.getValue();
            String shown = m.size() > 30 ? String.join(", ", m.subList(0, 30)) + ", ..." : String.join(", ", m);
            reply(c, e.getKey() + " (" + m.size() + "): " + shown);
        }
        return byFaction.size();
    }

    // ---------- kits ----------

    private static int kitCreate(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String kit = StringArgumentType.getString(c, "kit").toLowerCase();
        if (!KitManager.VALID.matcher(kit).matches()) return fail(c, "Kit names: 1-32 chars of a-z, 0-9, _ or -.");
        ServerPlayer player = c.getSource().getPlayerOrException();
        int n = mgr().kits.save(kit, player);
        if (n < 0) return fail(c, "Could not save kit (see log).");
        return reply(c, "Saved kit '" + kit + "' with " + n + " item stacks from your inventory.");
    }

    private static int kitLoad(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "name");
        String kit = StringArgumentType.getString(c, "kit");
        BotPlayer bot = mgr().find(c.getSource().getServer(), name);
        if (bot == null) return fail(c, "No active bot named " + name);
        int n = mgr().kits.load(kit, bot);
        if (n < 0) return fail(c, "Kit '" + kit + "' is missing or unreadable.");
        return reply(c, "Gave " + name + " kit '" + kit + "' (" + n + " item stacks).");
    }

    private static int kitLoadAll(CommandContext<CommandSourceStack> c) {
        String kit = StringArgumentType.getString(c, "kit");
        if (!mgr().kits.exists(kit)) return fail(c, "No kit named '" + kit + "'.");
        int done = 0;
        for (BotPlayer b : mgr().activeBots(c.getSource().getServer())) {
            if (mgr().kits.load(kit, b) >= 0) done++;
        }
        return reply(c, "Gave kit '" + kit + "' to " + done + " bots.");
    }

    private static int kitList(CommandContext<CommandSourceStack> c) {
        List<String> kits = mgr().kits.list();
        return reply(c, kits.isEmpty() ? "No kits saved. Use /bot kit create <name>." : "Kits: " + String.join(", ", kits));
    }

    private static int kitDelete(CommandContext<CommandSourceStack> c) {
        String kit = StringArgumentType.getString(c, "kit");
        if (!mgr().kits.delete(kit)) return fail(c, "No kit named '" + kit + "'.");
        return reply(c, "Deleted kit '" + kit + "'.");
    }
}
