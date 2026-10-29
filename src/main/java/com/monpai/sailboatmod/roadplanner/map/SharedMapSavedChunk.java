package com.monpai.sailboatmod.roadplanner.map;

import com.monpai.sailboatmod.route.terrain.SurfaceSample;
import com.monpai.sailboatmod.util.OfflineChunkPalette;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Decodes an exclusively-owned FULL NBT snapshot without touching a world or generating chunks. */
public final class SharedMapSavedChunk {
    private SharedMapSavedChunk() { }

    public static int[] read(CompoundTag tag, int chunkX, int chunkZ, int minY, int maxY) {
        return read(tag, null, chunkX, chunkZ, minY, maxY);
    }

    public static int[] read(CompoundTag tag, CompoundTag northTag, int chunkX, int chunkZ, int minY, int maxY) {
        OfflineChunkPalette.OfflineChunkBlocks blocks = OfflineChunkPalette.decode(tag, minY, maxY);
        if (blocks.isEmpty()) return new int[0];
        OfflineChunkPalette.OfflineChunkBlocks north = OfflineChunkPalette.decode(northTag, minY, maxY);
        Map<CompoundTag, BlockState> states = new HashMap<>();
        int[] pixels = SharedMapTerrainRenderer.render((worldX, worldZ, bps) -> {
            int x = worldX - chunkX * 16;
            int z = worldZ - chunkZ * 16;
            if (x < 0 || x >= 16 || z < -1 || z >= 16) return Optional.empty();
            OfflineChunkPalette.OfflineChunkBlocks source = z == -1 ? north : blocks;
            if (source.isEmpty()) return Optional.empty();
            int localZ = Math.floorMod(z, 16);
            int y = source.firstAvailableHeight(x, localZ, OfflineChunkPalette::isDefaultSurfaceNoise) - 1;
            if (y < minY) return Optional.empty();
            CompoundTag entry = source.paletteTag(x, y, localZ);
            if (entry == null) return Optional.empty();
            BlockState state = states.computeIfAbsent(entry,
                    key -> NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), key));
            boolean water = MapBlockColors.isWaterSurface(state);
            int depth = 0;
            if (water) {
                for (int below = y; below >= minY; below--) {
                    CompoundTag lower = source.paletteTag(x, below, localZ);
                    if (lower == null || !MapBlockColors.isWaterSurface(states.computeIfAbsent(lower,
                            key -> NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), key)))) break;
                    depth++;
                }
            }
            return Optional.of(new SurfaceSample(y, state, water, depth));
        }, chunkX * 16, chunkZ * 16, 16, 16);
        return Arrays.stream(pixels).anyMatch(color -> (color >>> 24) != 0) ? pixels : new int[0];
    }
}
