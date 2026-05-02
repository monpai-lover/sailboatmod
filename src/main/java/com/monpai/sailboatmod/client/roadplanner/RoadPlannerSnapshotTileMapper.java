package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadMapSnapshotSyncPacket;

import java.util.ArrayList;
import java.util.List;

public final class RoadPlannerSnapshotTileMapper {
    private RoadPlannerSnapshotTileMapper() {
    }

    public static List<TilePixel> map(RoadMapSnapshotSyncPacket packet) {
        if (packet == null || packet.pixelWidth() <= 0 || packet.pixelHeight() <= 0) {
            return List.of();
        }
        int[] pixels = packet.argbPixels();
        if (pixels.length != packet.pixelWidth() * packet.pixelHeight()) {
            return List.of();
        }
        int lod = packet.lod().blocksPerPixel();
        int minX = packet.regionCenterX() - packet.regionSize() / 2;
        int minZ = packet.regionCenterZ() - packet.regionSize() / 2;
        List<TilePixel> mapped = new ArrayList<>(pixels.length * lod * lod);
        for (int pixelZ = 0; pixelZ < packet.pixelHeight(); pixelZ++) {
            for (int pixelX = 0; pixelX < packet.pixelWidth(); pixelX++) {
                int argb = pixels[pixelZ * packet.pixelWidth() + pixelX];
                for (int dz = 0; dz < lod; dz++) {
                    for (int dx = 0; dx < lod; dx++) {
                        int worldX = minX + pixelX * lod + dx;
                        int worldZ = minZ + pixelZ * lod + dz;
                        int tileX = Math.floorDiv(worldX, RoadPlannerTile.TILE_SIZE_BLOCKS);
                        int tileZ = Math.floorDiv(worldZ, RoadPlannerTile.TILE_SIZE_BLOCKS);
                        int localX = Math.floorMod(worldX, RoadPlannerTile.TILE_SIZE_BLOCKS);
                        int localZ = Math.floorMod(worldZ, RoadPlannerTile.TILE_SIZE_BLOCKS);
                        mapped.add(new TilePixel(tileX, tileZ, localX, localZ, argb));
                    }
                }
            }
        }
        return List.copyOf(mapped);
    }

    public record TilePixel(int tileX, int tileZ, int localX, int localZ, int argb) {
    }
}
