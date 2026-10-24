package dev.aibots.mixin;

import com.mojang.authlib.GameProfile;
import dev.aibots.bot.BotNetHandler;
import dev.aibots.bot.BotPlayer;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hooks bots into the vanilla join/respawn flow (same technique Carpet uses). */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Shadow
    @Final
    private MinecraftServer server;

    @Inject(method = "placeNewPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;level()Lnet/minecraft/server/level/ServerLevel;"))
    private void aibots$fixStartingPos(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        if (player instanceof BotPlayer bot) {
            bot.fixStartingPosition.run();
        }
    }

    @Redirect(method = "placeNewPlayer", at = @At(value = "NEW", target = "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)Lnet/minecraft/server/network/ServerGamePacketListenerImpl;"))
    private ServerGamePacketListenerImpl aibots$replaceNetworkHandler(MinecraftServer server, Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
        if (player instanceof BotPlayer bot) {
            return new BotNetHandler(this.server, connection, bot, cookie);
        }
        return new ServerGamePacketListenerImpl(this.server, connection, player, cookie);
    }

    @Redirect(method = "respawn", at = @At(value = "NEW", target = "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerLevel;Lcom/mojang/authlib/GameProfile;Lnet/minecraft/server/level/ClientInformation;)Lnet/minecraft/server/level/ServerPlayer;"))
    private ServerPlayer aibots$makePlayerForRespawn(MinecraftServer srv, ServerLevel level, GameProfile profile, ClientInformation info, ServerPlayer old, boolean keepData) {
        if (old instanceof BotPlayer) {
            return BotPlayer.respawnBot(srv, level, profile, info);
        }
        return new ServerPlayer(srv, level, profile, info);
    }
}
