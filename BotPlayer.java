package dev.aibots.bot;

import com.mojang.authlib.GameProfile;
import dev.aibots.AiBotsMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.OldUsersConverter;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A server-side fake player. Follows the technique used by Carpet Mod /player bots
 * (MIT licensed): a real ServerPlayer with a network connection that goes nowhere.
 */
@SuppressWarnings("EntityConstructor")
public class BotPlayer extends ServerPlayer {
    private static final Set<String> SPAWNING = new HashSet<>();

    public Runnable fixStartingPosition = () -> {};

    private BotPlayer(MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation info) {
        super(server, level, profile, info);
    }

    public static boolean isSpawning(String name) {
        return SPAWNING.contains(name.toLowerCase());
    }

    public static int spawningCount() {
        return SPAWNING.size();
    }

    /**
     * Starts spawning a bot. Returns false if the name could not be resolved to an account and
     * offline bots are disabled. The bot itself appears a moment later (profile/skin is fetched
     * asynchronously); onSpawned is called on the server thread once it exists.
     */
    public static boolean create(MinecraftServer server, String username, ServerLevel level, Vec3 pos,
                                 double yaw, double pitch, GameType mode, Consumer<BotPlayer> onSpawned) {
        // Look up the real account (this is what gives the real skin). Same calls Carpet makes.
        server.services().nameToIdCache().resolveOfflineUsers(false);
        UUID uuid = OldUsersConverter.convertMobOwnerIfNecessary(server, username);
        if (uuid == null && AiBotsMod.config().allowOfflineBots) {
            server.services().nameToIdCache().resolveOfflineUsers(server.isDedicatedServer() && server.usesAuthentication());
            uuid = UUIDUtil.createOfflinePlayerUUID(username);
        }
        if (uuid == null) {
            return false;
        }
        final GameProfile baseProfile = new GameProfile(uuid, username);
        final String key = baseProfile.name().toLowerCase();
        SPAWNING.add(key);

        ResolvableProfile.createUnresolved(baseProfile.id())
                .resolveProfile(server.services().profileResolver())
                .whenCompleteAsync((resolved, error) -> {
                    SPAWNING.remove(key);
                    if (error != null) {
                        AiBotsMod.LOG.warn("Could not resolve profile for bot {}: {}", username, error.toString());
                        return;
                    }
                    GameProfile profile = (resolved == null || resolved.name().isEmpty()) ? baseProfile : resolved;
                    try {
                        BotPlayer bot = new BotPlayer(server, level, profile, ClientInformation.createDefault());
                        bot.fixStartingPosition = () -> bot.snapTo(pos.x, pos.y, pos.z, (float) yaw, (float) pitch);
                        server.getPlayerList().placeNewPlayer(new BotConnection(PacketFlow.SERVERBOUND), bot,
                                new CommonListenerCookie(profile, 0, bot.clientInformation(), false));
                        loadPlayerData(bot);
                        bot.stopRiding();
                        bot.teleportTo(level, pos.x, pos.y, pos.z, Set.of(), (float) yaw, (float) pitch, true);
                        bot.setHealth(20.0F);
                        bot.unsetRemoved();
                        bot.getAttribute(Attributes.STEP_HEIGHT).setBaseValue(0.6F);
                        bot.gameMode.changeGameModeForPlayer(mode);
                        ResourceKey<Level> dim = level.dimension();
                        server.getPlayerList().broadcastAll(new ClientboundRotateHeadPacket(bot, (byte) (bot.yHeadRot * 256 / 360)), dim);
                        server.getPlayerList().broadcastAll(ClientboundEntityPositionSyncPacket.of(bot), dim);
                        bot.entityData.set(DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7f); // show all skin layers
                        if (onSpawned != null) {
                            onSpawned.accept(bot);
                        }
                    } catch (Exception e) {
                        AiBotsMod.LOG.error("Failed to spawn bot " + username, e);
                    }
                }, server);
        return true;
    }

    private static void loadPlayerData(BotPlayer player) {
        try (ProblemReporter.ScopedCollector collector = new ProblemReporter.ScopedCollector(player.problemPath(), AiBotsMod.LOG)) {
            Optional<ValueInput> input = player.level().getServer().getPlayerList().loadPlayerData(player.nameAndId())
                    .map(tag -> TagValueInput.create(collector, player.registryAccess(), tag));
            input.ifPresent(in -> {
                player.load(in);
                player.loadAndSpawnEnderPearls(in);
                player.loadAndSpawnParentVehicle(in);
            });
        }
    }

    public static BotPlayer respawnBot(MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation info) {
        return new BotPlayer(server, level, profile, info);
    }

    /** Removes the bot from the server (it "logs off"). */
    public void kill(Component reason) {
        shakeOff();
        MinecraftServer server = this.level().getServer();
        server.schedule(new TickTask(server.getTickCount(), () ->
                this.connection.onDisconnect(new DisconnectionDetails(reason))));
    }

    @Override
    public void kill(ServerLevel level) {
        kill(Component.literal("Killed"));
    }

    @Override
    public void tick() {
        if (this.level().getServer().getTickCount() % 10 == 0) {
            this.connection.resetPosition();
            this.level().getChunkSource().move(this);
        }
        try {
            super.tick();
            this.doTick();
        } catch (NullPointerException ignored) {
            // Defensive, same as Carpet: never let a bot crash the server tick.
        }
    }

    @Override
    public void die(DamageSource cause) {
        shakeOff();
        super.die(cause);
        MinecraftServer server = this.level().getServer();
        if (AiBotsMod.config().botLeaveOnDeath) {
            // Bot leaves the server when it dies.
            setHealth(20);
            this.foodData = new FoodData();
            kill(this.getCombatTracker().getDeathMessage());
        } else {
            // Bot respawns like a normal player pressing "Respawn".
            server.schedule(new TickTask(server.getTickCount() + 1, () -> {
                if (this.isDeadOrDying()) {
                    this.connection.handleClientCommand(
                            new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                }
            }));
        }
    }

    private void shakeOff() {
        if (getVehicle() instanceof Player) stopRiding();
        for (Entity passenger : getIndirectPassengers()) {
            if (passenger instanceof Player) passenger.stopRiding();
        }
    }

    @Override
    public String getIpAddress() {
        return "127.0.0.1";
    }

    @Override
    public boolean allowsListing() {
        return true;
    }

    @Override
    protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) {
        doCheckFallDamage(0.0, y, 0.0, onGround);
    }

    @Override
    public ServerPlayer teleport(TeleportTransition transition) {
        super.teleport(transition);
        if (wonGame) {
            connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        }
        if (connection.player.isChangingDimension()) {
            connection.player.hasChangedDimension();
        }
        return connection.player;
    }
}
