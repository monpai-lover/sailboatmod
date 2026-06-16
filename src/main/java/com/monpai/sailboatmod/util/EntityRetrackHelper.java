package com.monpai.sailboatmod.util;

import com.mojang.logging.LogUtils;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.List;

/**
 * 强制让附近客户端重新接收某实体的 spawn 包。
 *
 * <p>多模组环境下，用区块强加载票据加载进来的移动实体（自动驾驶的马车/帆船到站时）可能从未被
 * {@link ChunkMap} 的 EntityTracker 向附近玩家 pair —— 服务端实体存活、有音效、可被扫描，但客户端
 * 从未收到 {@code ClientboundAddEntityPacket}，表现为「看不见但有声音、判定箱也框不到」。单 mod 无此问题。
 *
 * <p>实现：直接给「追踪本实体所在区块」的玩家补发完整 spawn 包族（add-entity + data + motion + passengers）。
 * 纯原版 API、零反射（早期反射 ChunkMap.TrackedEntity 的 SRG 名在生产 reobf jar 下不稳，已弃用）。
 * 单次发包可能错过客户端建立区块追踪的窗口（表现为「过几秒才出现」），故调用方应在到站后连续多 tick
 * 调用本方法，覆盖该窗口直至实体稳定可见。
 */
public final class EntityRetrackHelper {
    private static final Logger LOGGER = LogUtils.getLogger();

    private EntityRetrackHelper() {
    }

    /**
     * 给附近玩家补发本实体的完整 spawn 包族。
     *
     * @return 实际发包的玩家数（0 表示当前无追踪玩家）。
     */
    public static int resendSpawnToNearby(ServerLevel level, Entity entity) {
        if (level == null || entity == null || !entity.isAddedToWorld()) {
            return 0;
        }
        ChunkMap chunkMap = level.getChunkSource().chunkMap;
        ChunkPos chunkPos = new ChunkPos(entity.blockPosition());
        List<ServerPlayer> trackers = chunkMap.getPlayers(chunkPos, false);
        if (trackers == null || trackers.isEmpty()) {
            return 0;
        }
        var nonDefault = entity.getEntityData().getNonDefaultValues();
        int sent = 0;
        for (ServerPlayer player : trackers) {
            if (player == null || player.connection == null) {
                continue;
            }
            player.connection.send(entity.getAddEntityPacket());
            if (nonDefault != null && !nonDefault.isEmpty()) {
                player.connection.send(new ClientboundSetEntityDataPacket(entity.getId(), nonDefault));
            }
            player.connection.send(new ClientboundSetEntityMotionPacket(entity));
            if (!entity.getPassengers().isEmpty()) {
                player.connection.send(new ClientboundSetPassengersPacket(entity));
            }
            sent++;
        }
        return sent;
    }
}
