package com.monpai.sailboatmod.route.terrain;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tellus 世界的地形预测器入口;非 Tellus 世界返回空(调用方走旧流程)。 */
public final class TellusTerrain {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private TellusTerrain() {
    }

    public static Optional<TerrainPredictor> forLevel(ServerLevel level) {
        if (!TellusWorlds.isTellusLevel(level)) {
            return Optional.empty();
        }
        PrefetchGate gate;
        try {
            gate = new com.monpai.sailboatmod.compat.tellus.TellusPrefetchGate(level);
        } catch (LinkageError e) {
            // Tellus 版本变动导致预取 API 不兼容:预取与进度停用,预测仍走原版 getBaseColumn。
            if (WARNED.compareAndSet(false, true)) {
                LOGGER.warn("[WaterPath] Tellus prefetch API unavailable, predicting without prefetch: {}", e.toString());
            }
            gate = PrefetchGate.ALWAYS_READY;
        }
        return Optional.of(GeneratorColumnPredictor.forLevel(level, gate));
    }
}
