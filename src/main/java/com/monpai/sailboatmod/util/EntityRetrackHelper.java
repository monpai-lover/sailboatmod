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
 * 强制让附近客户端重新接收并持续追踪某实体。
 *
 * <p>多模组环境下，用区块强加载票据加载进来的移动实体（自动驾驶的马车/帆船）可能从未被
 * {@link ChunkMap} 的 EntityTracker 向附近玩家 pair —— 服务端实体存活、有音效、可被扫描，但客户端
 * 从未收到 {@code ClientboundAddEntityPacket}，表现为「看不见但有声音、判定箱也框不到」（幽灵车/船）。
 *
 * <p>根治 = {@link #retrackViaVanilla}：用 vanilla {@code ServerChunkCache.removeEntity+addEntity} 让 vanilla
 * 自己重建 TrackedEntity 并对全体玩家重新评估 pairing（发 spawn）。之后位置同步全交给 vanilla，平滑不抽搐。
 * 不再自己周期重发 spawn/teleport（那会砸客户端实体导致动画重播/抽搐，已删）。
 */
public final class EntityRetrackHelper {
    private static final Logger LOGGER = LogUtils.getLogger();

    private EntityRetrackHelper() {
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

    /**
     * <b>2026-06 幽灵车彻底修复</b>:让 <b>vanilla</b> 完整重建本实体的 EntityTracker 并对所有玩家重新评估 pairing,
     * 而非自己硬塞 spawn/teleport 包。
     *
     * <p><b>真因</b>(实测+反编译 ChunkMap 确认):载具靠 ForgeChunkManager ENTITY_TICKING 票据强加载自己周围、
     * 远航途中自行 tick,但玩家不在附近时载具所在区块不在玩家的 entity-ticking 范围;vanilla {@code ChunkMap.tick()}
     * 只在实体「跨 section 移动」那一帧才 {@code updatePlayers} 重新评估 pairing,时序一旦错过(玩家进范围时载具
     * 恰未跨 section / 距离判定边界),{@code TrackedEntity.updatePlayer} 不被调 → {@code seenBy} 不加该玩家 →
     * {@code serverEntity.addPairing} 不发 spawn → 客户端永远没这个实体(幽灵车)。注释 chunkMap.getPlayers
     * 返回「区块在视野」≠「实体已 pair」,所以诊断里 vanillaTracks=true 仍幽灵。
     *
     * <p><b>修法</b>:{@code ServerChunkCache.removeEntity} + {@code addEntity}(都是 public vanilla API)。
     * removeEntity 清掉旧 TrackedEntity 并对已 pair 客户端 broadcastRemoved;addEntity 重建 TrackedEntity 并
     * {@code updatePlayers(level.players())} 对<b>全体玩家</b>重新评估距离+broadcastToPlayer → 范围内的客户端
     * 由 vanilla 自己 addPairing 发完整 spawn。之后位置同步全交给 vanilla 的 {@code serverEntity.sendChanges()}
     * (平滑、不抽搐),<b>无需再自己发 teleport/motion</b>。
     *
     * <p>代价:对已正常显示的客户端是一次「移除+重加」,会让 GeckoLib 动画重播一次。故<b>低频</b>调用(每 1~2 秒,
     * 而非每帧),且只在 autopilot 远航期间。比旧的「每帧 teleport 硬拽 + 每 3 秒重发 AddEntity」抽搐轻得多,且是
     * 纯 vanilla 流程。乘客一律跳过(乘客客户端必然已正确 pair,移除+重加会砸乘客菜单/动画)。
     *
     * @return true 表示执行了一次 vanilla retrack。
     */
    public static boolean retrackViaVanilla(ServerLevel level, Entity entity) {
        if (level == null || entity == null || !entity.isAddedToWorld() || entity.isRemoved()) {
            return false;
        }
        // 有乘客时跳过:移除+重加会把乘客客户端正常工作的实体砸掉(动画重播、菜单关闭)。
        // 乘客坐得上车本就证明其客户端已正确 pair,无需 retrack。
        if (!entity.getPassengers().isEmpty()) {
            return false;
        }
        try {
            var chunkSource = level.getChunkSource();
            chunkSource.removeEntity(entity); // 清旧 TrackedEntity + 对已 pair 客户端 broadcastRemoved
            chunkSource.addEntity(entity);    // 重建 TrackedEntity + updatePlayers 全员重新评估 pairing(发 spawn)
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[Retrack] vanilla retrack 失败 entity={}", entity.getId(), t);
            return false;
        }
    }
}
