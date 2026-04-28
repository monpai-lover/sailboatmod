package com.monpai.sailboatmod.client.roadplanner;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
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
                result.setPixelRGBA(x, z, reliefColor(level, new BlockPos(worldX, worldY, worldZ)));
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
        if (state.getFluidState().is(Fluids.WATER)) {
            BlockState topState = topWaterBlock(level, pos);
            MapColor mapColor = topState.getMapColor(level, pos);
            if (mapColor == null) {
                mapColor = MapColor.WATER;
            }
            int depth = waterDepth(level, pos);
            MapColor.Brightness brightness = depth > 6 ? MapColor.Brightness.LOWEST
                    : depth > 3 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
            return RoadPlannerMapPalette.softenWater(0xFF000000 | mapColor.calculateRGBColor(brightness));
        }
        MapColor mapColor = state.getMapColor(level, pos);
        if (mapColor == null) {
            return 0x00000000;
        }
        int heightHere = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        int heightSouth = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ() + 1);
        int heightWest = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX() - 1, pos.getZ());
        int relief = heightHere - Math.max(heightSouth, heightWest);
        MapColor.Brightness brightness = relief > 2 ? MapColor.Brightness.HIGH
                : relief > 0 ? MapColor.Brightness.NORMAL
                : relief > -2 ? MapColor.Brightness.LOW : MapColor.Brightness.LOWEST;
        return RoadPlannerMapPalette.softenTerrain(0xFF000000 | mapColor.calculateRGBColor(brightness));
    }

    private BlockState topWaterBlock(ClientLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos mutable = pos.mutable();
        while (level.getBlockState(mutable).getFluidState().is(Fluids.WATER) && mutable.getY() < level.getMaxBuildHeight()) {
            mutable.move(Direction.UP);
        }
        return level.getBlockState(mutable.below());
    }

    private int waterDepth(ClientLevel level, BlockPos pos) {
        int depth = 0;
        BlockPos.MutableBlockPos mutable = pos.mutable();
        while (level.getBlockState(mutable).getFluidState().is(Fluids.WATER) && mutable.getY() > level.getMinBuildHeight()) {
            depth++;
            mutable.move(Direction.DOWN);
        }
        return depth;
    }

    @Override
    public void close() {
        image.close();
    }
}
