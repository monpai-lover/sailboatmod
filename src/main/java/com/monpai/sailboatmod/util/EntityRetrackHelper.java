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
import java.util.Set;
import java.util.UUID;

/**
 * 强制让附近客户端重新接收某实体的 spawn 包。
 *
 * <p>多模组环境下，用区块强加载票据加载进来的移动实体（自动驾驶的马车/帆船）可能从未被
 * {@link ChunkMap} 的 EntityTracker 向附近玩家 pair —— 服务端实体存活、有音效、可被扫描，但客户端
 * 从未收到 {@code ClientboundAddEntityPacket}，表现为「看不见但有声音、判定箱也框不到」。单 mod 无此问题。
 *
 * <p>实现：直接给「视野范围内」的玩家补发完整 spawn 包族（add-entity + data + motion + passengers）。
 * 纯原版 API、零反射（早期反射 ChunkMap.TrackedEntity 的 SRG 名在生产 reobf jar 下不稳，已弃用）。
 */
public final class EntityRetrackHelper {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 视野/追踪范围（格）。略大于原版实体常规 tracking range，保证玩家一进视野即覆盖。 */
    private static final double RETRACK_RANGE = 80.0D;
    private static final double RETRACK_RANGE_SQR = RETRACK_RANGE * RETRACK_RANGE;

    private EntityRetrackHelper() {
    }

    /**
     * 只对「新进入视野范围、本实体尚未对其补发过 spawn」的玩家补发 spawn 包族。
     *
     * <p>每 tick 调用开销极小：仅当有新玩家进入时才发包；玩家离开范围则从 {@code alreadySpawned} 移除，
     * 下次再进入会重新补发。实现「车辆一进入玩家视野就恢复可视」且不重复轰炸已可见玩家。
     *
     * @param alreadySpawned 由调用实体持有的可变集合，记录已补发过 spawn 的玩家 UUID（本方法就地增删）。
     * @return 本次新补发 spawn 的玩家数。
     */
    public static int resendSpawnToNewTrackers(ServerLevel level, Entity entity, Set<UUID> alreadySpawned) {
        if (level == null || entity == null || alreadySpawned == null || !entity.isAddedToWorld()) {
            return 0;
        }
        double ex = entity.getX();
        double ey = entity.getY();
        double ez = entity.getZ();
        var nonDefault = entity.getEntityData().getNonDefaultValues();
        boolean hasPassengers = !entity.getPassengers().isEmpty();

        java.util.HashSet<UUID> inRangeNow = new java.util.HashSet<>();
        int sent = 0;
        for (ServerPlayer player : level.players()) {
            if (player == null || player.connection == null) {
                continue;
            }
            if (player.distanceToSqr(ex, ey, ez) > RETRACK_RANGE_SQR) {
                continue;
            }
            UUID id = player.getUUID();
            inRangeNow.add(id);
            if (alreadySpawned.contains(id)) {
                continue; // 已对其补发过、仍在范围内 → 不重复发
            }
            // 新进入范围的玩家：补发完整 spawn 包族。
            player.connection.send(entity.getAddEntityPacket());
            if (nonDefault != null && !nonDefault.isEmpty()) {
                player.connection.send(new ClientboundSetEntityDataPacket(entity.getId(), nonDefault));
            }
            player.connection.send(new ClientboundSetEntityMotionPacket(entity));
            if (hasPassengers) {
                player.connection.send(new ClientboundSetPassengersPacket(entity));
            }
            alreadySpawned.add(id);
            sent++;
        }
        // 离开范围的玩家移除，下次再进入会重新补发（覆盖「走开又回来」）。
        alreadySpawned.retainAll(inRangeNow);
        return sent;
    }

    /**
     * 给附近玩家补发本实体的完整 spawn 包族（无状态版，到站宽限期连续重发用）。
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
