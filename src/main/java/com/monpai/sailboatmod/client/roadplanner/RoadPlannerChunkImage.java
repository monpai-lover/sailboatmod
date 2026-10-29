package com.monpai.sailboatmod.client.roadplanner;

import com.mojang.blaze3d.platform.NativeImage;
import com.monpai.sailboatmod.roadplanner.map.MapBlockColors;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRenderStyle;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

public class RoadPlannerChunkImage implements AutoCloseable {
    private final NativeImage image;

    public RoadPlannerChunkImage(ClientLevel level, ChunkPos chunkPos) {
        this.image = generateVanillaStyleImage(level, chunkPos);
    }

    public NativeImage image() {
        return image;
    }

    public boolean isMeaningful() {
        int meaningful = 0;
        for (int index = 0; index < 256; index++) {
            int pixel = image.getPixelRGBA(index % 16, index / 16);
            int alpha = (pixel >> 24) & 0xFF;
            int rgb = pixel & 0x00FFFFFF;
            if (alpha > 0 && rgb != 0) {
                meaningful++;
            }
        }
        return meaningful >= 25;
    }

    private NativeImage generateVanillaStyleImage(ClientLevel level, ChunkPos chunkPos) {
        NativeImage result = new NativeImage(NativeImage.Format.RGBA, 16, 16, true);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int worldX = chunkPos.getMinBlockX() + x;
                int worldZ = chunkPos.getMinBlockZ() + z;
                int worldY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ) - 1;
                int argb = reliefColor(level, new BlockPos(worldX, worldY, worldZ));
                int nativeAbgr = (argb >>> 24) == 0 ? 0 : MapBlockColors.argbToNativeAbgr(argb);
                result.setPixelRGBA(x, z, nativeAbgr);
            }
        }
        result.untrack();
        return result;
    }

    private int reliefColor(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return 0x00000000;
        }
        if (MapBlockColors.isWaterSurface(state)) {
            return waterArgbForDepth(waterDepth(level, pos));
        }
        MapColor mapColor = state.getMapColor(level, pos);
        if (mapColor == null) {
            return 0x00000000;
        }
        int northZ = pos.getZ() - 1;
        int northHeight = pos.getY();
        if (level.getChunkSource().getChunk(Math.floorDiv(pos.getX(), 16), Math.floorDiv(northZ, 16), false) != null) {
            northHeight = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), northZ) - 1;
        }
        int fallback = mapColor.calculateRGBColor(MapColor.Brightness.NORMAL);
        return RoadMapRenderStyle.styleTerrainByDelta(MapBlockColors.colorFor(state, fallback), pos.getY() - northHeight);
    }

    static int terrainArgbForMapColor(MapColor mapColor, int relief) {
        if (mapColor == null) {
            return 0x00000000;
        }
        int base = mapColor == MapColor.GRASS ? MapBlockColors.lookup("minecraft:grass_block")
                : 0xFF000000 | mapColor.calculateRGBColor(MapColor.Brightness.NORMAL);
        return RoadMapRenderStyle.styleTerrainByDelta(base, relief);
    }

    static int waterArgbForDepth(int depth) {
        return waterArgbForMapColor(MapColor.WATER, depth);
    }

    static int waterArgbForMapColor(MapColor mapColor, int depth) {
        return RoadMapRenderStyle.styleWater(depth);
    }

    private int waterDepth(ClientLevel level, BlockPos pos) {
        int depth = 0;
        BlockPos.MutableBlockPos mutable = pos.mutable();
        while (MapBlockColors.isWaterSurface(level.getBlockState(mutable)) && mutable.getY() >= level.getMinBuildHeight()) {
            depth++;
            mutable.move(net.minecraft.core.Direction.DOWN);
        }
        return depth;
    }

    @Override
    public void close() {
        image.close();
    }
}
