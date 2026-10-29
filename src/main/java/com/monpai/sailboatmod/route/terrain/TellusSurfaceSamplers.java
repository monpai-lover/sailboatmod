package com.monpai.sailboatmod.route.terrain;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicBoolean;

/** 网页地图预测层的 Tellus 取样器入口;非 Tellus 世界或 Tellus API 不兼容时为空。 */
public final class TellusSurfaceSamplers {
    /** spike 实测:zoom 0–4 单张瓦片在 30 秒内,zoom 8 约 25 分钟;更远的层级只显示真实世界底图。 */
    public static final int MAX_TILE_ZOOM = 4;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private TellusSurfaceSamplers() {
    }

    public static Optional<TerrainSurfaceSampler> forLevel(ServerLevel level) {
        if (!TellusWorlds.isTellusLevel(level)) {
            return Optional.empty();
        }
        try {
            return checked(() -> new com.monpai.sailboatmod.compat.tellus.TellusSurfaceSampler(level),
                    com.monpai.sailboatmod.compat.tellus.TellusSurfaceSampler::checkApi);
        } catch (LinkageError e) {
            warnOnce(e);
            return Optional.empty();
        }
    }

    /** 能在不触发下载的情况下验证 Tellus API(反射查方法签名)。 */
    @FunctionalInterface
    public interface ApiCheck {
        void check() throws ReflectiveOperationException;
    }

    /**
     * 先验证 Tellus API 再建立取样器:JVM 延迟解析方法,版本变动造成的 NoSuchMethodError 否则会在每个像素取样时才出现、
     * 被当成普通取样失败,预测层就一直宣告可用却一张也画不出来。不兼容 → 空(预测层关闭,只剩真实世界底图)。
     */
    static <T> Optional<T> checked(java.util.function.Supplier<T> factory, ApiCheck apiCheck) {
        try {
            apiCheck.check();
            return Optional.of(factory.get());
        } catch (LinkageError e) {
            warnOnce(e);
            return Optional.empty();
        } catch (ReflectiveOperationException e) {
            warnOnce(new LinkageError(e.toString(), e));
            return Optional.empty();
        }
    }

    public static OptionalDouble worldScale(ServerLevel level) {
        if (!TellusWorlds.isTellusLevel(level)) {
            return OptionalDouble.empty();
        }
        try {
            return OptionalDouble.of(com.monpai.sailboatmod.compat.tellus.TellusSurfaceSampler.worldScale(level));
        } catch (LinkageError e) {
            warnOnce(e);
            return OptionalDouble.empty();
        }
    }

    private static void warnOnce(LinkageError e) {
        if (WARNED.compareAndSet(false, true)) {
            LOGGER.warn("[WebMap] Tellus surface API unavailable, Tellus map tiles disabled: {}", e.toString());
        }
    }
}
