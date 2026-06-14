package com.monpai.sailboatmod.market;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 按维度记录已加载的市场方块坐标，供后台调度遍历（避免逐 chunk 扫）。 */
public final class MarketRegistry {
    private static final Map<ResourceKey<Level>, Set<BlockPos>> MARKETS = new ConcurrentHashMap<>();

    public static void register(Level level, BlockPos pos) {
        MARKETS.computeIfAbsent(level.dimension(), key -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                .add(pos.immutable());
    }

    public static void unregister(Level level, BlockPos pos) {
        Set<BlockPos> set = MARKETS.get(level.dimension());
        if (set != null) {
            set.remove(pos);
            if (set.isEmpty()) {
                MARKETS.remove(level.dimension());
            }
        }
    }

    public static Set<BlockPos> get(Level level) {
        Set<BlockPos> set = MARKETS.get(level.dimension());
        if (set == null || set.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(set);
    }

    private MarketRegistry() {
    }
}
