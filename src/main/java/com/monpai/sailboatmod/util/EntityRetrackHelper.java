package com.monpai.sailboatmod.util;

import com.mojang.logging.LogUtils;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * 强制让附近客户端重新追踪并接收某实体的 spawn 包。
 *
 * <p>多模组环境下，用区块强加载票据加载进来的移动实体（自动驾驶的马车/帆船到站时）可能从未被
 * {@link ChunkMap} 的 EntityTracker 向附近玩家 pair —— 服务端实体存活、有音效、可被扫描，但客户端
 * 从未收到 {@code ClientboundAddEntityPacket}，表现为「看不见但有声音、判定箱也框不到」。单 mod 无此问题。
 *
 * <p>主路径：反射拿到 ChunkMap.entityMap 里本实体的 {@code TrackedEntity}，对每个追踪玩家先
 * {@code removePlayer} 再 {@code updatePlayer}，逼它重发完整 spawn+data+motion+passengers。
 * 反射失败（SRG 名变动等）则降级原版兜底：直接给追踪本区块的玩家发 add-entity + data + passengers。
 */
public final class EntityRetrackHelper {
    private static final Logger LOGGER = LogUtils.getLogger();

    // ChunkMap.entityMap (Int2ObjectMap<ChunkMap.TrackedEntity>)
    private static final String F_ENTITY_MAP = "f_140150_";
    // ChunkMap.TrackedEntity#updatePlayer(ServerPlayer) / #removePlayer(ServerPlayer)
    private static final String M_UPDATE_PLAYER = "m_140491_";
    private static final String M_REMOVE_PLAYER = "m_140488_";

    private static boolean reflectionResolved = false;
    private static boolean reflectionFailed = false;
    private static Field entityMapField;
    private static Class<?> trackedEntityClass;
    private static Method updatePlayerMethod;
    private static Method removePlayerMethod;

    private EntityRetrackHelper() {
    }

    /**
     * 对指定实体强制重发 spawn 给附近玩家。
     *
     * @return true 表示走通了反射 untrack→retrack 主路径；false 表示降级到原版兜底（或无追踪玩家）。
     */
    public static boolean forceRetrack(ServerLevel level, Entity entity) {
        if (level == null || entity == null || !entity.isAddedToWorld()) {
            return false;
        }
        ChunkMap chunkMap = level.getChunkSource().chunkMap;
        ChunkPos chunkPos = new ChunkPos(entity.blockPosition());
        List<ServerPlayer> trackers = chunkMap.getPlayers(chunkPos, false);
        if (trackers == null || trackers.isEmpty()) {
            return false;
        }

        if (tryReflectiveRetrack(chunkMap, entity, trackers)) {
            return true;
        }
        vanillaResendSpawn(entity, trackers);
        return false;
    }

    private static boolean tryReflectiveRetrack(ChunkMap chunkMap, Entity entity, List<ServerPlayer> trackers) {
        resolveReflection();
        if (reflectionFailed) {
            return false;
        }
        try {
            Object map = entityMapField.get(chunkMap);
            // Int2ObjectMap#get(int) —— 用 Map#get(Object) 反射通用调用，避免直接依赖 fastutil 签名。
            Object tracked = map.getClass().getMethod("get", int.class).invoke(map, entity.getId());
            if (tracked == null || !trackedEntityClass.isInstance(tracked)) {
                return false;
            }
            for (ServerPlayer player : trackers) {
                removePlayerMethod.invoke(tracked, player);
                updatePlayerMethod.invoke(tracked, player);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            reflectionFailed = true;
            LOGGER.warn("[EntityRetrackHelper] reflective retrack failed, falling back to vanilla resend: {}",
                    ex.toString());
            return false;
        }
    }

    /** 原版兜底：直接给追踪玩家补发 add-entity + entity-data + passengers。 */
    private static void vanillaResendSpawn(Entity entity, List<ServerPlayer> trackers) {
        var nonDefault = entity.getEntityData().getNonDefaultValues();
        for (ServerPlayer player : trackers) {
            player.connection.send(entity.getAddEntityPacket());
            if (nonDefault != null && !nonDefault.isEmpty()) {
                player.connection.send(new ClientboundSetEntityDataPacket(entity.getId(), nonDefault));
            }
            if (!entity.getPassengers().isEmpty()) {
                player.connection.send(new ClientboundSetPassengersPacket(entity));
            }
        }
    }

    private static synchronized void resolveReflection() {
        if (reflectionResolved || reflectionFailed) {
            return;
        }
        try {
            entityMapField = ObfuscationReflectionHelper.findField(ChunkMap.class, F_ENTITY_MAP);
            entityMapField.setAccessible(true);
            for (Class<?> inner : ChunkMap.class.getDeclaredClasses()) {
                if (inner.getSimpleName().equals("TrackedEntity")) {
                    trackedEntityClass = inner;
                    break;
                }
            }
            if (trackedEntityClass == null) {
                throw new IllegalStateException("ChunkMap.TrackedEntity not found");
            }
            updatePlayerMethod = ObfuscationReflectionHelper.findMethod(
                    trackedEntityClass, M_UPDATE_PLAYER, ServerPlayer.class);
            removePlayerMethod = ObfuscationReflectionHelper.findMethod(
                    trackedEntityClass, M_REMOVE_PLAYER, ServerPlayer.class);
            updatePlayerMethod.setAccessible(true);
            removePlayerMethod.setAccessible(true);
            reflectionResolved = true;
        } catch (RuntimeException ex) {
            reflectionFailed = true;
            LOGGER.warn("[EntityRetrackHelper] cannot resolve ChunkMap reflection (SRG mismatch?): {}", ex.toString());
        }
    }
}
