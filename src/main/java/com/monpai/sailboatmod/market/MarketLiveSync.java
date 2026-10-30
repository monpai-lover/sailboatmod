package com.monpai.sailboatmod.market;

import com.monpai.sailboatmod.block.entity.MarketBlockEntity;
import com.monpai.sailboatmod.menu.MarketMenu;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.MarketLiveUpdatePacket;
import com.monpai.sailboatmod.network.packet.OpenMarketScreenPacket;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps open market screens current. Before, a screen only changed when its own player acted or reopened it, so
 * other players' purchases, listings, cancellations and order progress stayed invisible. Now every write bumps
 * {@link MarketChangeNotifier}; each server tick this pushes a fresh overview to at most one viewer whose data is
 * older than the revision, the same viewer at most once a second ({@link MarketLiveSchedule}).
 */
public final class MarketLiveSync {
    static final int MIN_INTERVAL_TICKS = 20;

    private static final MarketLiveSchedule<UUID> SCHEDULE = new MarketLiveSchedule<>(MIN_INTERVAL_TICKS);

    private MarketLiveSync() {
    }

    /** Answers a player's own action or refresh; the next push for this viewer waits for a newer change. */
    public static void sendOverview(ServerPlayer player, MarketBlockEntity market) {
        long revision = MarketChangeNotifier.revision();
        ModNetwork.CHANNEL.send(player, new OpenMarketScreenPacket(market.buildOverview(player)));
        SCHEDULE.sent(player.getUUID(), GlobalPos.of(player.level().dimension(), market.getBlockPos()), revision,
                player.server.getTickCount());
    }

    /** Server thread, once per tick. */
    public static void tick(MinecraftServer server) {
        Map<UUID, GlobalPos> open = new HashMap<>();
        Map<UUID, ServerPlayer> players = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof MarketMenu menu) {
                open.put(player.getUUID(), GlobalPos.of(player.level().dimension(), menu.getMarketPos()));
                players.put(player.getUUID(), player);
            }
        }
        long revision = MarketChangeNotifier.revision();
        long tick = server.getTickCount();
        UUID due = SCHEDULE.next(open, revision, tick);
        if (due == null) {
            return;
        }
        ServerPlayer player = players.get(due);
        GlobalPos pos = open.get(due);
        if (player.level().getBlockEntity(pos.pos()) instanceof MarketBlockEntity market) {
            ModNetwork.CHANNEL.send(player, new MarketLiveUpdatePacket(market.buildOverview(player)));
        }
        SCHEDULE.sent(due, pos, revision, tick);
    }

    /** Tick counts restart with each server; forget viewers from the previous one. */
    public static void reset() {
        SCHEDULE.clear();
    }
}
