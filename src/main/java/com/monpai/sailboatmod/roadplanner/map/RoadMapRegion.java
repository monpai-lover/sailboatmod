package com.monpai.sailboatmod.roadplanner.map;

import net.minecraft.core.BlockPos;

import java.util.Objects;

public record RoadMapRegion(BlockPos center, int regionSize, MapLod lod) {
    public RoadMapRegion {
        center = Objects.requireNonNull(center, "center").immutable();
        lod = Objects.requireNonNull(lod, "lod");
        if (regionSize <= 0) {
            throw new IllegalArgumentException("regionSize must be positive");
        }
        if (regionSize % lod.blocksPerPixel() != 0) {
            throw new IllegalArgumentException("regionSize must be divisible by lod blocksPerPixel");
        }
    }

    public static RoadMapRegion centeredOn(BlockPos center, int regionSize, MapLod lod) {
        return new RoadMapRegion(center, regionSize, lod);
    }

    public int minX() {
        return center.getX() - regionSize / 2;
    }

    public int maxXExclusive() {
        return minX() + regionSize;
    }

    public int minZ() {
        return center.getZ() - regionSize / 2;
    }

    public int maxZExclusive() {
        return minZ() + regionSize;
    }

    public int pixelWidth() {
        return regionSize / lod.blocksPerPixel();
    }

    public int pixelHeight() {
        return regionSize / lod.blocksPerPixel();
    }

    public boolean containsWorldXZ(int worldX, int worldZ) {
        return worldX >= minX() && worldX < maxXExclusive() && worldZ >= minZ() && worldZ < maxZExclusive();
    }

    public RoadMapPoint worldToGui(BlockPos worldPos, RoadMapViewport viewport) {
        double guiX = viewport.left() + ((worldPos.getX() - minX()) / (double) regionSize) * viewport.width();
        double guiY = viewport.top() + ((worldPos.getZ() - minZ()) / (double) regionSize) * viewport.height();
        return new RoadMapPoint(guiX, guiY);
    }

    public BlockPos guiToWorldXZ(double guiX, double guiY, RoadMapViewport viewport) {
        int worldX = (int) Math.floor(minX() + ((guiX - viewport.left()) / viewport.width()) * regionSize);
        int worldZ = (int) Math.floor(minZ() + ((guiY - viewport.top()) / viewport.height()) * regionSize);
        return new BlockPos(worldX, 0, worldZ);
    }
}
