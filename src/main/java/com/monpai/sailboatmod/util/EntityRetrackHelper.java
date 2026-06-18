package com.monpai.sailboatmod.util;

import com.mojang.logging.LogUtils;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
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
 * 强制让附近客户端重新接收并持续追踪某实体。
 *
 * <p>多模组环境下，用区块强加载票据加载进来的移动实体（自动驾驶的马车/帆船）可能从未被
 * {@link ChunkMap} 的 EntityTracker 向附近玩家 pair —— 服务端实体存活、有音效、可被扫描，但客户端
 * 从未收到 {@code ClientboundAddEntityPacket}，表现为「看不见但有声音、判定箱也框不到」。单 mod 无此问题。
 *
 * <p>仅补发一次 spawn 不够：EntityTracker 坏掉后客户端收不到后续移动包，spawn 出来的实体会僵在原地
 * 甚至被丢弃。故对视野范围内玩家：新玩家补发完整 spawn 包族，且对所有范围内玩家**每次都补发绝对位置
 * (teleport)+motion**，自行替代 EntityTracker 的移动同步，使返航途中实体跟着动、稳定可见。
 * 纯原版 API、零反射（早期反射 ChunkMap.TrackedEntity 的 SRG 名在生产 reobf jar 下不稳，已弃用）。
 */
public final class EntityRetrackHelper {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 视野/追踪范围（格）。略大于原版实体常规 tracking range，保证玩家一进视野即覆盖。 */
    private static final double RETRACK_RANGE = 96.0D;
    private static final double RETRACK_RANGE_SQR = RETRACK_RANGE * RETRACK_RANGE;

    private EntityRetrackHelper() {
    }

    /**
     * 对视野范围内玩家维持可见。多 mod 坏掉的 EntityTracker 环境下，spawn 包被客户端丢弃后 teleport 对
     * 客户端不存在的实体无效 → 返航全程看不见、只到站才出现（实测 2026-06 确认）。
     *
     * <p>模型分两路：
     * <ul>
     *   <li><b>每帧</b>对范围内所有玩家发 teleport+motion —— 平滑移动，替代失效的 EntityTracker 移动同步。</li>
     *   <li><b>spawn 包族</b>（add+data+passengers）在玩家<i>刚进入范围</i>时发一次（让马车出现），
     *       且在 {@code spawnHeartbeat=true} 的帧对范围内<i>所有</i>玩家重发一次 —— 兜底「首次 spawn 被
     *       客户端丢弃后实体永远建不起来」的幽灵车回归（实测 2026-06：只发一次会退回幽灵车）。调用方应
     *       周期性（约每秒一帧）置 {@code spawnHeartbeat=true}，而非每帧（每帧重发会令客户端插值重置→渲染抽搐）。</li>
     * </ul>
     * 原版 {@code ClientboundAddEntityPacket} 按 entity-id 幂等覆盖，重发安全。
     *
     * @param alreadySpawned 由调用实体持有的可变集合，记录当前仍在范围内的玩家 UUID（本方法就地增删）。
     * @param spawnHeartbeat true 时对范围内所有玩家重发一次 spawn 包族（心跳帧 / 到站收尾用）；false 时只对刚进范围的新玩家发。
     * @return 本次补发 spawn 包族的玩家数。
     */
    public static int resendSpawnToNewTrackers(ServerLevel level, Entity entity, Set<UUID> alreadySpawned,
                                               boolean spawnHeartbeat) {
        if (level == null || entity == null || alreadySpawned == null || !entity.isAddedToWorld()) {
            return 0;
        }
        double ex = entity.getX();
        double ey = entity.getY();
        double ez = entity.getZ();
        var nonDefault = entity.getEntityData().getNonDefaultValues();
        boolean hasPassengers = !entity.getPassengers().isEmpty();
        ClientboundTeleportEntityPacket teleport = new ClientboundTeleportEntityPacket(entity);
        ClientboundSetEntityMotionPacket motion = new ClientboundSetEntityMotionPacket(entity);

        // 本实体的乘客集合(UUID)：乘客客户端必然已正确追踪本实体(否则上不去/坐不住),对其重发 spawn
        // 只会砸掉正常工作的客户端实体——GeckoLib 动画从头重播(每3秒抽搐)、打开的容器菜单被关闭。
        // 故心跳重发跳过乘客,乘客只靠下面的 teleport+motion 维持移动同步。
        java.util.HashSet<UUID> passengerIds = new java.util.HashSet<>();
        for (Entity passenger : entity.getPassengers()) {
            if (passenger != null) {
                passengerIds.add(passenger.getUUID());
            }
        }

        java.util.HashSet<UUID> inRangeNow = new java.util.HashSet<>();
        int spawned = 0;
        for (ServerPlayer player : level.players()) {
            if (player == null || player.connection == null) {
                continue;
            }
            if (player.distanceToSqr(ex, ey, ez) > RETRACK_RANGE_SQR) {
                continue;
            }
            UUID id = player.getUUID();
            inRangeNow.add(id);
            boolean isNew = !alreadySpawned.contains(id);
            boolean isPassenger = passengerIds.contains(id);
            // 新进范围发一次 spawn 让船/车出现；心跳帧对范围内玩家重发兜底被丢弃的实体——但乘客除外
            // (乘客已正确追踪,重发会砸动画/关菜单)。乘客即使首次也不在此发 spawn(上船时已正常 spawn)。
            if ((isNew || spawnHeartbeat) && !isPassenger) {
                player.connection.send(entity.getAddEntityPacket());
                if (nonDefault != null && !nonDefault.isEmpty()) {
                    player.connection.send(new ClientboundSetEntityDataPacket(entity.getId(), nonDefault));
                }
                if (hasPassengers) {
                    player.connection.send(new ClientboundSetPassengersPacket(entity));
                }
                alreadySpawned.add(id);
                spawned++;
            }
            // 所有范围内玩家：每帧补发绝对位置 + 速度，替代失效的 EntityTracker 移动同步（平滑移动）。
            player.connection.send(teleport);
            player.connection.send(motion);
        }
        // 离开范围的玩家移除，下次再进入会作为「新玩家」重新补发 spawn（覆盖「走开又回来」）。
        alreadySpawned.retainAll(inRangeNow);
        return spawned;
    }

    /**
     * 给附近玩家补发本实体的完整 spawn 包族（无状态版，帆船 stopAutopilot 一次性用）。
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
