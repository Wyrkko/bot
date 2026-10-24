package dev.aibots.bot;

import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;

import java.util.Set;

public class BotNetHandler extends ServerGamePacketListenerImpl {
    public BotNetHandler(MinecraftServer server, Connection connection, BotPlayer bot, CommonListenerCookie cookie) {
        super(server, connection, bot, cookie);
    }

    @Override
    public void disconnect(Component message) {
        if (message.getContents() instanceof TranslatableContents text
                && (text.getKey().equals("multiplayer.disconnect.idling")
                || text.getKey().equals("multiplayer.disconnect.duplicate_login"))) {
            ((BotPlayer) player).kill(message);
        }
    }

    @Override
    public void teleport(PositionMoveRotation pos, Set<Relative> relatives) {
        super.teleport(pos, relatives);
        if (player.level().getPlayerByUUID(player.getUUID()) != null) {
            resetPosition();
            player.level().getChunkSource().move(player);
        }
    }
}
