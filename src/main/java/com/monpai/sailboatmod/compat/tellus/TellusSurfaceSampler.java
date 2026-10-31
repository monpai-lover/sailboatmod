package com.monpai.sailboatmod.compat.tellus;

import com.monpai.sailboatmod.route.terrain.PreloadSampleInterpreter;
import com.monpai.sailboatmod.route.terrain.SurfaceBlockGrid;
import com.monpai.sailboatmod.route.terrain.SurfaceSample;
import com.monpai.sailboatmod.route.terrain.TerrainSurfaceSampler;
import com.yucareux.tellus.preload.TerrainPreloadPackage;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.TellusWorldgenSources;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

/**
 * Tellus 专属地表取样(网页地图预测层)。每像素用 {@code samplePreloadTerrain}(与生成方块相同的原生解析度,热数据约 2–4 µs);
 * 陆地地表方块:层级 0 用 {@code resolveLodSurfaceBlock}(每 4×4 像素解析一次),更远层级按土地覆盖分类取代表方块。水 = 非陆地或 ESA WorldCover 80(永久水体),
 * 不用 WaterSurfaceResolver(远景逐点下载完整解析度数据)。见 tellus-surface-spike-findings。
 */
public final class TellusSurfaceSampler implements TerrainSurfaceSampler {
    private static final int GRID_CAPACITY = 16_384;

    @FunctionalInterface
    interface NativeSampleSource {
        NativeSample sample(int x, int z, double resolutionMeters);
    }

    @FunctionalInterface
    interface SurfaceBlockSource {
        BlockState sample(int x, int z, int height);
    }

    record NativeSample(int terrainHeight, int coverClass, boolean land, boolean landMaskKnown) {
    }

    private final double worldScale;
    private final NativeSampleSource terrain;
    private final SurfaceBlockSource surface;
    private final int seaLevel;
    private final SurfaceBlockGrid blocks = new SurfaceBlockGrid(GRID_CAPACITY);

    public TellusSurfaceSampler(ServerLevel level) {
        this((EarthChunkGenerator) level.getChunkSource().getGenerator());
    }

    /** Same native sampler for server use and opt-in generator integration tests. */
    public TellusSurfaceSampler(EarthChunkGenerator earth) {
        EarthGeneratorSettings settings = earth.settings();
        this.worldScale = settings.worldScale();
        this.terrain = (x, z, resolutionMeters) -> {
            TerrainPreloadPackage.Sample sample = TellusWorldgenSources.samplePreloadTerrain(x, z, settings, resolutionMeters);
            return new NativeSample(sample.terrainHeight(), sample.coverClass(), sample.land(), sample.landMaskKnown());
        };
        this.surface = (x, z, height) -> earth.resolveLodSurfaceBlock(x, z, height, false);
        this.seaLevel = earth.getSeaLevel();
    }

    TellusSurfaceSampler(double worldScale, int seaLevel, NativeSampleSource terrain, SurfaceBlockSource surface) {
        this.worldScale = worldScale;
        this.seaLevel = seaLevel;
        this.terrain = terrain;
        this.surface = surface;
    }

    public static double worldScale(ServerLevel level) {
        return ((EarthChunkGenerator) level.getChunkSource().getGenerator()).settings().worldScale();
    }

    /** 不触发任何下载,只用反射确认本类用到的 Tellus API 都还在。 */
    public static void checkApi() throws ReflectiveOperationException {
        TellusWorldgenSources.class.getMethod("samplePreloadTerrain", int.class, int.class, EarthGeneratorSettings.class, double.class);
        for (String accessor : new String[]{"terrainHeight", "coverClass", "land", "landMaskKnown"}) {
            TerrainPreloadPackage.Sample.class.getMethod(accessor);
        }
        EarthChunkGenerator.class.getMethod("resolveLodSurfaceBlock", int.class, int.class, int.class, boolean.class);
        EarthChunkGenerator.class.getMethod("settings");
        EarthGeneratorSettings.class.getMethod("worldScale");
    }

    @Override
    public Optional<SurfaceSample> sample(int x, int z, int blocksPerSample) {
        try {
            int bps = Math.max(1, blocksPerSample);
            // 像素间距只控制显示密度。按缩放降低 DEM 解析度会改变同一方块的海床高度与水深色阶,
            // 与已生成的真实方块拼接时形成矩形接缝。
            NativeSample s = terrain.sample(x, z, worldScale);
            int height = s.terrainHeight();
            return PreloadSampleInterpreter.interpret(height, s.coverClass(), s.land(), s.landMaskKnown(), seaLevel, bps,
                    () -> blocks.get(x, z, bps, (cx, cz) -> surface.sample(cx, cz, height)));
        } catch (RuntimeException e) {
            // 取样失败(例外)→ 透明像素;LinkageError 不在这里吞掉,建立前已由 checkApi 验证。
            return Optional.empty();
        }
    }
}
