package com.monpai.sailboatmod.market.web.map;

import com.mojang.logging.LogUtils;
import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.roadplanner.map.MapBlockColors;
import com.monpai.sailboatmod.roadplanner.map.RoadMapColorizer;
import com.monpai.sailboatmod.roadplanner.map.RoadMapColumnSample;
import com.monpai.sailboatmod.roadplanner.map.RoadMapServerColumnSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.common.world.ForgeChunkManager;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * Main-thread snapshot of one chunk's map columns.
 *
 * <p>The web map worker must never read {@link ServerLevel} directly. This is the local equivalent of
 * squaremap's ChunkSnapshot boundary: copy world data quickly on the server thread, then colorize and
 * write PNGs on worker threads.
 */
public record MarketWebMapChunkSnapshot(
        String dimensionId,
        int chunkX,
        int chunkZ,
        RoadMapColumnSample[] samples
) {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int UNKNOWN_ARGB = 0xFF2A2A2A;

    public static Optional<MarketWebMapChunkSnapshot> capture(ServerLevel level, int chunkX, int chunkZ) {
        if (level == null || !MarketWebMapConstants.OVERWORLD.equals(level.dimension().location().toString())) {
            return Optional.empty();
        }
        // 纵深防御:getChunk 在非主线程上会 join 主线程(死锁源)。worker 线程绝不该调到这里——
        // 真要调到了就安全降级(丢一帧,下次扫描会补),绝不阻塞 join。
        if (!level.getServer().isSameThread()) {
            LOGGER.warn("MarketWebMapChunkSnapshot.capture called off the server thread for chunk {},{} — skipping to avoid blocking getChunk", chunkX, chunkZ);
            return Optional.empty();
        }
        ChunkAccess chunk = level.getChunkSource().getChunk(chunkX, chunkZ, false);
        if (chunk == null) {
            return Optional.empty();
        }
        return Optional.of(captureLoaded(level, chunkX, chunkZ));
    }

    /**
     * Squaremap-style generated chunk gate.
     *
     * <p>Unloaded chunks are never scheduled into Minecraft's chunk system by the web map. If the
     * chunk is already loaded, we snapshot it. If it is only present on disk, we read the saved NBT
     * and decode {@code sections[].block_states} into an immutable map snapshot.</p>
     */
    public static Optional<MarketWebMapChunkSnapshot> captureGenerated(ServerLevel level, int chunkX, int chunkZ) {
        if (level == null || !MarketWebMapConstants.OVERWORLD.equals(level.dimension().location().toString())) {
            return Optional.empty();
        }
        // 同 capture:getChunk 与 chunkMap.read().getNow 都假定在主线程;非主线程安全降级。
        if (!level.getServer().isSameThread()) {
            LOGGER.warn("MarketWebMapChunkSnapshot.captureGenerated called off the server thread for chunk {},{} — skipping to avoid blocking getChunk", chunkX, chunkZ);
            return Optional.empty();
        }
        ChunkAccess loaded = level.getChunkSource().getChunk(chunkX, chunkZ, false);
        if (loaded != null) {
            return Optional.of(captureLoaded(level, chunkX, chunkZ));
        }
        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        return readGeneratedChunkTag(level, chunkPos)
                .filter(MarketWebMapChunkSnapshot::isFullChunkTag)
                .flatMap(tag -> MarketWebMapNbtChunkSnapshotReader.capture(
                        MarketWebMapConstants.OVERWORLD,
                        chunkX,
                        chunkZ,
                        tag,
                        level.getMinBuildHeight(),
                        level.getMaxBuildHeight()));
    }

    /**
     * <b>主线程</b>:对【已生成】区块 force 加载后采样(squaremap 模式安全拿区块方式)。
     *
     * <p>调用方负责先经 probe 确认该 chunk 已生成存盘(Status=full),并对未加载 chunk 才走到这里。这里
     * force 该区块(同 tick 同步完成磁盘读),getChunkNow 取出走与已加载 chunk 完全相同的 {@link #captureLoaded}
     * 主线程采样,try/finally 即采即放票据不常驻。</p>
     *
     * <p><b>为什么安全</b>:整个流程都在主线程,绝不把共享 {@link CompoundTag} 交给后台线程并发 datafix 改写
     * (那是崩服根因)。force 已生成只是同步读盘,比 force 生成轻;配额节流由调用方负责。</p>
     */
    public static Optional<MarketWebMapChunkSnapshot> captureGeneratedViaForce(ServerLevel level, int chunkX, int chunkZ) {
        if (level == null || !MarketWebMapConstants.OVERWORLD.equals(level.dimension().location().toString())) {
            return Optional.empty();
        }
        if (!level.getServer().isSameThread()) {
            LOGGER.warn("MarketWebMapChunkSnapshot.captureGeneratedViaForce called off the server thread for chunk {},{} — skipping to avoid unsafe chunk access", chunkX, chunkZ);
            return Optional.empty();
        }
        ChunkAccess loaded = level.getChunkSource().getChunk(chunkX, chunkZ, false);
        if (loaded != null) {
            return Optional.of(captureLoaded(level, chunkX, chunkZ));
        }
        BlockPos owner = new BlockPos(chunkX << 4, level.getSeaLevel(), chunkZ << 4);
        boolean forced = false;
        try {
            forced = ForgeChunkManager.forceChunk(level, SailboatMod.MODID, owner, chunkX, chunkZ, true, false);
            LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
            if (chunk == null) {
                return Optional.empty();
            }
            return Optional.of(captureLoaded(level, chunkX, chunkZ));
        } catch (RuntimeException exception) {
            return Optional.empty();
        } finally {
            if (forced) {
                try {
                    ForgeChunkManager.forceChunk(level, SailboatMod.MODID, owner, chunkX, chunkZ, false, false);
                } catch (RuntimeException ignored) {
                    // 释放失败不致命:存档卸载时随票据自然清理。
                }
            }
        }
    }

    private static MarketWebMapChunkSnapshot captureLoaded(ServerLevel level, int chunkX, int chunkZ) {
        RoadMapServerColumnSampler sampler = new RoadMapServerColumnSampler(level);
        RoadMapColumnSample[] samples = new RoadMapColumnSample[MarketWebMapConstants.CHUNK_SIZE * MarketWebMapConstants.CHUNK_SIZE];
        int baseX = chunkX * MarketWebMapConstants.CHUNK_SIZE;
        int baseZ = chunkZ * MarketWebMapConstants.CHUNK_SIZE;
        for (int localZ = 0; localZ < MarketWebMapConstants.CHUNK_SIZE; localZ++) {
            for (int localX = 0; localX < MarketWebMapConstants.CHUNK_SIZE; localX++) {
                samples[localZ * MarketWebMapConstants.CHUNK_SIZE + localX] = sampler.sample(baseX + localX, baseZ + localZ);
            }
        }
        return new MarketWebMapChunkSnapshot(MarketWebMapConstants.OVERWORLD, chunkX, chunkZ, samples);
    }

    private static MarketWebMapChunkSnapshot captureChunkAccess(ServerLevel level, ChunkAccess chunk) {
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        RoadMapColumnSample[] samples = new RoadMapColumnSample[MarketWebMapConstants.CHUNK_SIZE * MarketWebMapConstants.CHUNK_SIZE];
        int baseX = chunkX * MarketWebMapConstants.CHUNK_SIZE;
        int baseZ = chunkZ * MarketWebMapConstants.CHUNK_SIZE;
        for (int localZ = 0; localZ < MarketWebMapConstants.CHUNK_SIZE; localZ++) {
            for (int localX = 0; localX < MarketWebMapConstants.CHUNK_SIZE; localX++) {
                samples[localZ * MarketWebMapConstants.CHUNK_SIZE + localX] =
                        sampleChunk(level, chunk, baseX + localX, baseZ + localZ, localX, localZ);
            }
        }
        return new MarketWebMapChunkSnapshot(MarketWebMapConstants.OVERWORLD, chunkX, chunkZ, samples);
    }

    private static Optional<CompoundTag> readGeneratedChunkTag(ServerLevel level, ChunkPos chunkPos) {
        try {
            return level.getChunkSource().chunkMap.read(chunkPos).getNow(Optional.empty());
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static boolean isFullChunkTag(CompoundTag tag) {
        if (tag == null || !tag.contains("Status")) {
            return false;
        }
        String status = tag.getString("Status");
        return "full".equals(status) || "minecraft:full".equals(status);
    }

    private static RoadMapColumnSample sampleChunk(ServerLevel level,
                                                   ChunkAccess chunk,
                                                   int worldX,
                                                   int worldZ,
                                                   int localX,
                                                   int localZ) {
        try {
            int surfaceY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, localX, localZ) - 1;
            if (surfaceY < level.getMinBuildHeight()) {
                return RoadMapServerColumnSampler.unavailableSampleForTest(worldX, worldZ);
            }
            BlockPos pos = new BlockPos(worldX, surfaceY, worldZ);
            BlockState state = chunk.getBlockState(pos);
            boolean water = MapBlockColors.isWaterSurface(state);
            int waterDepth = water ? waterDepth(level, chunk, pos) : 0;
            int reliefBaseY = Math.max(
                    chunkHeightOrFallback(chunk, localX, localZ + 1, surfaceY + 1),
                    chunkHeightOrFallback(chunk, localX - 1, localZ, surfaceY + 1));
            MapColor mapColor = state.getMapColor(level, pos);
            int fallback = mapColor == null ? UNKNOWN_ARGB : mapColor.calculateRGBColor(MapColor.Brightness.NORMAL);
            int argb = water ? MapBlockColors.waterArgb() : MapBlockColors.colorFor(state, fallback);
            return new RoadMapColumnSample(worldX, surfaceY, worldZ, argb, water, waterDepth, reliefBaseY);
        } catch (RuntimeException exception) {
            return RoadMapServerColumnSampler.unavailableSampleForTest(worldX, worldZ);
        }
    }

    private static int chunkHeightOrFallback(ChunkAccess chunk, int localX, int localZ, int fallback) {
        if (localX < 0 || localX >= MarketWebMapConstants.CHUNK_SIZE
                || localZ < 0 || localZ >= MarketWebMapConstants.CHUNK_SIZE) {
            return fallback;
        }
        return chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, localX, localZ);
    }

    private static int waterDepth(ServerLevel level, ChunkAccess chunk, BlockPos pos) {
        int depth = 0;
        BlockPos.MutableBlockPos mutable = pos.mutable();
        while (MapBlockColors.isWaterSurface(chunk.getBlockState(mutable)) && mutable.getY() > level.getMinBuildHeight()) {
            depth++;
            mutable.move(net.minecraft.core.Direction.DOWN);
        }
        return depth;
    }

    public int[] colorize(RoadMapColorizer colorizer) {
        RoadMapColorizer safeColorizer = colorizer == null ? new RoadMapColorizer() : colorizer;
        int[] pixels = new int[MarketWebMapConstants.CHUNK_SIZE * MarketWebMapConstants.CHUNK_SIZE];
        for (int index = 0; index < pixels.length; index++) {
            RoadMapColumnSample sample = samples == null || index >= samples.length ? null : samples[index];
            pixels[index] = sample == null ? 0x00000000 : safeColorizer.color(sample);
        }
        return pixels;
    }
}
