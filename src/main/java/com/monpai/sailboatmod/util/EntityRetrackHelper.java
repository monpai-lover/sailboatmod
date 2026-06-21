package com.monpai.sailboatmod.util;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

/**
 * 到站收尾一次性让 vanilla 重建本实体的 EntityTracker 并对所有玩家重新评估 pairing。
 *
 * <p>背景:autopilot 载具用 ForgeChunkManager ENTITY_TICKING 票据强加载自己周围、远航途中自行 tick。vanilla
 * {@code ChunkMap.tick()} 只在实体「跨 section 移动」那一帧才 {@code updatePlayers} 重新评估 pairing,时序一旦错过
 * (玩家进范围时载具恰未跨 section / 距离判定边界),{@code TrackedEntity.updatePlayer} 不被调 → vanilla 不发 spawn →
 * 这种「与 EntityCulling 无关」的罕见 pairing 时序幽灵,到站调一次本方法兜底重建即可。
 *
 * <p><b>注</b>:autopilot 远航期间常见的「远处看不见」幽灵,真因是客户端 EntityCulling mod 的视线遮挡剔除,已由
 * 给载具设 {@code noCulling=true}(SailboatEntity/CarriageEntity 构造)治本,<b>不再用周期 retrack 心跳</b>(那套补发包
 * 逻辑方向错了,已删)。([[entity_culling_ghost_noculling]])
 */
public final class EntityRetrackHelper {
    private static final Logger LOGGER = LogUtils.getLogger();

    private EntityRetrackHelper() {
    }

    /**
     * 让 vanilla 完整重建本实体的 EntityTracker 并对全体玩家重新评估 pairing(到站一次性兜底)。
     *
     * <p>{@code ServerChunkCache.removeEntity}(清旧 TrackedEntity + 对已 pair 客户端 broadcastRemoved) +
     * {@code addEntity}(重建 TrackedEntity + updatePlayers 全员重评估 → 范围内客户端由 vanilla addPairing 发完整 spawn)。
     * 之后位置同步交给 vanilla {@code serverEntity.sendChanges()}(平滑)。
     *
     * <p>代价:对已显示客户端是一次「移除+重加」,GeckoLib 动画重播一次。故只到站调一次(低频),不每帧/每 3s 调。
     * 乘客跳过(乘客客户端必然已正确 pair,移除+重加会砸其菜单/动画)。
     *
     * @return true 表示执行了一次 vanilla retrack。
     */
    public static boolean retrackViaVanilla(ServerLevel level, Entity entity) {
        if (level == null || entity == null || !entity.isAddedToWorld() || entity.isRemoved()) {
            return false;
        }
        if (!entity.getPassengers().isEmpty()) {
            return false; // 乘客客户端已 pair,移除+重加会砸其菜单/动画,跳过
        }
        try {
            var chunkSource = level.getChunkSource();
            chunkSource.removeEntity(entity);
            chunkSource.addEntity(entity);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[Retrack] vanilla retrack 失败 entity={}", entity.getId(), t);
            return false;
        }
    }
}
