package com.monpai.sailboatmod.route.terrain;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

/**
 * Tellus 地球世界判定(可选依赖):Tellus 已加载 + 主世界 + 生成器类名是 EarthChunkGenerator。按类名比较,
 * 不触发加载任何 Tellus 类;装了 Tellus 但世界用普通预设时返回 false,走旧流程。
 */
public final class TellusWorlds {
    public static final String MOD_ID = "tellus";
    public static final String EARTH_GENERATOR = "com.yucareux.tellus.worldgen.EarthChunkGenerator";

    private TellusWorlds() {
    }

    public static boolean isTellusGenerator(String generatorClassName, boolean tellusLoaded, boolean overworld) {
        return tellusLoaded && overworld && EARTH_GENERATOR.equals(generatorClassName);
    }

    public static boolean isTellusLevel(ServerLevel level) {
        if (level == null) {
            return false;
        }
        ModList mods = ModList.get();
        boolean loaded = mods != null && mods.isLoaded(MOD_ID);
        return isTellusGenerator(level.getChunkSource().getGenerator().getClass().getName(), loaded,
                Level.OVERWORLD.equals(level.dimension()));
    }
}
