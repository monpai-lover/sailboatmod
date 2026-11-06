package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.route.terrain.Prediction;
import com.monpai.sailboatmod.route.terrain.PredictionBudget;
import com.monpai.sailboatmod.route.terrain.PrefetchGate;
import com.monpai.sailboatmod.route.terrain.SurfaceSample;
import com.monpai.sailboatmod.route.terrain.TerrainPredictor;
import com.monpai.sailboatmod.route.terrain.TerrainSurfaceSampler;
import com.monpai.sailboatmod.route.water.tellus.PmTilesLandMask;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** Shared official-mask fixture for the complete production planning/post-processing entry point. */
final class TellusMaskRouteFixture implements AutoCloseable {
    static final int SEA_Y = 63;
    final PmTilesLandMask mask;
    final double scale;

    TellusMaskRouteFixture(Path path, double scale) throws IOException {
        mask = new PmTilesLandMask(path, scale);
        this.scale = scale;
    }

    @Override
    public void close() throws IOException {
        mask.close();
    }

    BlockPos endpoint(double longitude, double latitude) {
        int cx = (int) Math.round(PmTilesLandMask.blockX(longitude, scale));
        int cz = (int) Math.round(PmTilesLandMask.blockZ(latitude, scale));
        for (int r = 0; r < 128; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
            boolean clear = true;
            for (int ox = -2; ox <= 2 && clear; ox++) for (int oz = -2; oz <= 2 && clear; oz++) {
                clear = !mask.isLand(cx + dx + ox, cz + dz + oz);
            }
            if (clear) return new BlockPos(cx + dx, SEA_Y, cz + dz);
        }
        throw new IllegalStateException("No 5x5-water endpoint near " + longitude + "," + latitude);
    }

    TerrainSurfaceSampler surface() {
        return (x, z, spacing) -> Optional.of(mask.isLand(x, z, mask.zoomFor(spacing))
                ? new SurfaceSample(SEA_Y + 2, Blocks.GRASS_BLOCK.defaultBlockState(), false, 0)
                : new SurfaceSample(SEA_Y, Blocks.WATER.defaultBlockState(), true, 8));
    }

    TerrainPredictor predictor() {
        return new TerrainPredictor() {
            @Override
            public Prediction predictChunk(int cx, int cz, long maxWaitMillis) {
                boolean[] water = new boolean[256];
                int[] heights = new int[256];
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    water[z * 16 + x] = !mask.isLand(cx * 16 + x, cz * 16 + z);
                    heights[z * 16 + x] = SEA_Y - 1;
                }
                return Prediction.ready(new NbtChunkWaterReader.ChunkColumns(water, heights));
            }

            @Override public PrefetchGate gate() { return PrefetchGate.ALWAYS_READY; }
        };
    }

    WaterRouteResult<List<BlockPos>> plan(BlockPos source, BlockPos target, long limitMillis) {
        return WaterAutoRouteServiceBench.plan(source, target, SEA_Y, predictor(),
                new PredictionBudget(limitMillis, System::currentTimeMillis), surface(), limitMillis, scale);
    }

    /** Independent quarter-block sampling of all four hull cells, without production verification helpers. */
    long assertHullWater(List<BlockPos> path) {
        long checked = 0;
        for (int i = 1; i < path.size(); i++) {
            BlockPos a = path.get(i - 1), b = path.get(i);
            int steps = Math.max(1, (int) Math.ceil(Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ()) * 4));
            for (int step = 0; step <= steps; step++) {
                double fraction = step / (double) steps;
                int x = (int) Math.round(a.getX() + (b.getX() - a.getX()) * fraction);
                int z = (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * fraction);
                for (int ox = 0; ox <= 1; ox++) for (int oz = 0; oz <= 1; oz++) {
                    assertFalse(mask.isLand(x + ox, z + oz), "2x2 hull crosses land at " + (x + ox) + "," + (z + oz));
                    checked++;
                }
            }
        }
        return checked;
    }

    void writeCsv(Path output, List<BlockPos> path) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        StringBuilder csv = new StringBuilder("x,z,lon,lat\n");
        for (BlockPos p : path) csv.append(p.getX()).append(',').append(p.getZ()).append(',')
                .append(PmTilesLandMask.lon(p.getX(), scale)).append(',')
                .append(PmTilesLandMask.lat(p.getZ(), scale)).append('\n');
        Files.writeString(output, csv);
    }
}
