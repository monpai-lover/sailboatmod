package com.monpai.sailboatmod.util;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * 强制让附近客户端重新接收并持续追踪某实体。
 *
 * <p>多模组环境下，用区块强加载票据加载进来的移动实体（自动驾驶的马车/帆船）可能从未被
 * {@link ChunkMap} 的 EntityTracker 向附近玩家 pair —— 服务端实体存活、有音效、可被扫描，但客户端
 * 从未收到 {@code ClientboundAddEntityPacket}，表现为「看不见但有声音、判定箱也框不到」（幽灵车/船）。
 *
 * <p>两条修法，按场景分用：
 * <ul>
 *   <li>{@link #retrackOnDemand}（autopilot 远航每 3s 心跳用）：反射拿本实体的 vanilla TrackedEntity，对追踪范围内
 *       每个玩家调 public {@code updatePlayer}。vanilla 内部 {@code seenBy.add} 幂等——<b>已 pair 的玩家什么都不做
 *       （零抽搐）</b>，仅幽灵玩家补发完整 spawn。这才是「按需」，不会像 removeEntity+addEntity 那样无差别砸所有
 *       已显示客户端导致 GeckoLib 动画每 3s 重播（心跳抽搐根因）。反射失败兜底退回 {@link #retrackViaVanilla}。</li>
 *   <li>{@link #retrackViaVanilla}（到港收尾一次性用）：{@code ServerChunkCache.removeEntity+addEntity} 让 vanilla
 *       完整重建 TrackedEntity 并对全体玩家重新评估 pairing。到港只调一次，不抽搐，确保到港即可见。</li>
 * </ul>
 * 不再自己周期重发 spawn/teleport（那会砸客户端实体导致动画重播/抽搐，已删）。
 */
public final class EntityRetrackHelper {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** ChunkMap.entityMap（Int2ObjectMap&lt;TrackedEntity&gt;，private）SRG 名；dev=official `entityMap`。 */
    private static final String ENTITY_MAP_SRG = "f_140150_";
    private static final String ENTITY_MAP_OFFICIAL = "entityMap";
    /** ChunkMap.TrackedEntity.updatePlayer(ServerPlayer)（public）SRG 名；dev=official `updatePlayer`。 */
    private static final String UPDATE_PLAYER_SRG = "m_140459_";
    private static final String UPDATE_PLAYER_OFFICIAL = "updatePlayer";

    // 反射句柄缓存（首次成功后复用；失败标记 reflectionBroken 以后直接走兜底，避免每 tick 抛异常刷日志）。
    private static Field entityMapField;
    private static Method updatePlayerMethod;
    private static boolean reflectionBroken;
    private static boolean warnedBroken;

    private EntityRetrackHelper() {
    }

    /**
     * <b>2026-06 心跳抽搐根因修复</b>:autopilot 远航的「每 3s 让远端客户端能看到船」改成 <b>vanilla 原生幂等按需 pair</b>,
     * 取代旧的无条件 {@link #retrackViaVanilla}（每 3s removeEntity+addEntity 会对<b>已经看得见船</b>的玩家也
     * broadcastRemoved→重 spawn → GeckoLib 动画每 3s 从头重播 = 用户看到的心跳抽搐）。
     *
     * <p><b>机制</b>（反编译 1.20.1 ChunkMap.TrackedEntity.updatePlayer 字节码确认）:updatePlayer 内部仅当
     * {@code seenBy.add(player.connection)} 返回 true（即该玩家此前<b>未</b> pair）才 {@code serverEntity.addPairing}
     * 发完整 spawn;已 pair 的玩家 add 返回 false → updatePlayer 直接返回、<b>什么都不发</b>。所以对范围内每个玩家
     * 调一次 updatePlayer = 幽灵玩家补 spawn、正常玩家零打扰。<b>只调 updatePlayer 不调 removePlayer</b>（remove 才是
     * 无差别砸客户端的根源）。
     *
     * <p><b>反射</b>:TrackedEntity 是 ChunkMap 的 package-private 内部类、entityMap 是 fastutil Int2ObjectMap（都不便
     * 直接 import/调用），故 entityMap 字段、其 {@code get(int)} 方法、updatePlayer 方法全走反射,字段/方法名按 SRG 优先
     * official 兜底双查找（生产 reobf=SRG,dev official）。任一步失败 → 标记 broken,本次及以后<b>兜底退回</b>
     * {@link #retrackViaVanilla}(= 旧行为,最坏不更糟)。
     *
     * <p>乘客一律跳过（乘客客户端必然已正确 pair,updatePlayer 对其也是 no-op,但提前 return 省反射开销且语义清晰）。
     *
     * @return true 表示执行了按需 retrack（或兜底执行了 vanilla retrack）。
     */
    public static boolean retrackOnDemand(ServerLevel level, Entity entity) {
        if (level == null || entity == null || !entity.isAddedToWorld() || entity.isRemoved()) {
            return false;
        }
        if (!entity.getPassengers().isEmpty()) {
            return false; // 乘客客户端已 pair,无需 retrack(且 removeEntity 兜底会砸乘客菜单/动画)
        }
        if (reflectionBroken) {
            return retrackViaVanilla(level, entity); // 反射不可用,直接兜底
        }
        try {
            ChunkMap chunkMap = level.getChunkSource().chunkMap;
            Object tracked = trackedEntityOf(chunkMap, entity.getId());
            if (tracked == null) {
                // vanilla 还没为此实体建 TrackedEntity → 退回 vanilla(addEntity 会建并 updatePlayers 全员评估)。
                return retrackViaVanilla(level, entity);
            }
            Method update = updatePlayerMethod(tracked.getClass());
            BlockPos pos = entity.blockPosition();
            List<ServerPlayer> players = chunkMap.getPlayers(new ChunkPos(pos), false);
            for (ServerPlayer p : players) {
                update.invoke(tracked, p); // vanilla 幂等:已 pair 玩家 no-op,幽灵玩家补 spawn
            }
            return true;
        } catch (Throwable t) {
            markReflectionBroken(t);
            return retrackViaVanilla(level, entity);
        }
    }

    /**
     * 反射取 chunkMap.entityMap.get(entityId) 的 TrackedEntity（package-private 内部类,以 Object 持有）。
     * entityMap 是 fastutil {@code Int2ObjectMap}（库类,不经 reobf,方法名恒为 {@code get(int)}）,用反射调 get
     * 避免直接 import fastutil。
     */
    private static Object trackedEntityOf(ChunkMap chunkMap, int entityId) throws ReflectiveOperationException {
        Field field = entityMapField();
        Object map = field.get(chunkMap);
        if (map == null) {
            throw new NoSuchFieldException("entityMap is null on " + chunkMap.getClass());
        }
        Method get = map.getClass().getMethod("get", int.class);
        return get.invoke(map, entityId);
    }

    /** 解析并缓存 ChunkMap.entityMap 字段（SRG 优先,dev official 兜底）。 */
    private static Field entityMapField() throws ReflectiveOperationException {
        if (entityMapField != null) {
            return entityMapField;
        }
        Field f = findField(ChunkMap.class, ENTITY_MAP_SRG, ENTITY_MAP_OFFICIAL);
        f.setAccessible(true);
        entityMapField = f;
        return f;
    }

    /** 解析并缓存 TrackedEntity.updatePlayer(ServerPlayer) 方法（SRG 优先,dev official 兜底）。 */
    private static Method updatePlayerMethod(Class<?> trackedClass) throws ReflectiveOperationException {
        if (updatePlayerMethod != null) {
            return updatePlayerMethod;
        }
        Method m = findMethod(trackedClass, ServerPlayer.class, UPDATE_PLAYER_SRG, UPDATE_PLAYER_OFFICIAL);
        m.setAccessible(true);
        updatePlayerMethod = m;
        return m;
    }

    /** 先试 SRG 名,失败再试 official 名（entityMap 声明在 ChunkMap 本类,不必沿继承链）。 */
    private static Field findField(Class<?> owner, String srg, String official) throws NoSuchFieldException {
        try {
            return owner.getDeclaredField(srg);
        } catch (NoSuchFieldException ignored) {
            return owner.getDeclaredField(official);
        }
    }

    /** 先试 SRG 名,失败再试 official 名（沿继承链查,含父类 public 方法）。 */
    private static Method findMethod(Class<?> owner, Class<?> paramType, String srg, String official)
            throws NoSuchMethodException {
        for (String name : new String[]{srg, official}) {
            Class<?> c = owner;
            while (c != null) {
                try {
                    return c.getDeclaredMethod(name, paramType);
                } catch (NoSuchMethodException ignored) {
                    c = c.getSuperclass();
                }
            }
        }
        throw new NoSuchMethodException("updatePlayer not found by SRG(" + srg + ")/official(" + official + ")");
    }

    private static void markReflectionBroken(Throwable t) {
        reflectionBroken = true;
        if (!warnedBroken) {
            warnedBroken = true;
            LOGGER.warn("[Retrack] retrackOnDemand 反射失败,以后退回 vanilla removeEntity+addEntity(可能心跳抽搐)", t);
        }
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
