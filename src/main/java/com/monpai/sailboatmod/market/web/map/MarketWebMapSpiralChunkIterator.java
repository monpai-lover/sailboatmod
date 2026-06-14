package com.monpai.sailboatmod.market.web.map;

import java.util.ArrayList;
import java.util.List;

/**
 * Center-out square spiral chunk ordering, matching squaremap's radius-render behavior closely
 * enough to avoid rendering whole region bounding boxes for small radius jobs.
 */
public final class MarketWebMapSpiralChunkIterator {
    private MarketWebMapSpiralChunkIterator() {
    }

    public static List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> squareSpiral(String dimensionId,
                                                                                 int centerChunkX,
                                                                                 int centerChunkZ,
                                                                                 int radiusChunks) {
        if (!MarketWebMapConstants.OVERWORLD.equals(dimensionId)) {
            return List.of();
        }
        int radius = Math.max(0, radiusChunks);
        List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> out = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
        out.add(new MarketWebMapDirtyChunkQueue.ChunkCoordinate(dimensionId, centerChunkX, centerChunkZ));
        for (int ring = 1; ring <= radius; ring++) {
            int x = centerChunkX + ring;
            int z = centerChunkZ - ring + 1;
            for (; z <= centerChunkZ + ring; z++) {
                out.add(new MarketWebMapDirtyChunkQueue.ChunkCoordinate(dimensionId, x, z));
            }
            x--;
            z--;
            for (; x >= centerChunkX - ring; x--) {
                out.add(new MarketWebMapDirtyChunkQueue.ChunkCoordinate(dimensionId, x, z));
            }
            x++;
            z--;
            for (; z >= centerChunkZ - ring; z--) {
                out.add(new MarketWebMapDirtyChunkQueue.ChunkCoordinate(dimensionId, x, z));
            }
            x++;
            z++;
            for (; x <= centerChunkX + ring; x++) {
                out.add(new MarketWebMapDirtyChunkQueue.ChunkCoordinate(dimensionId, x, z));
            }
        }
        return List.copyOf(out);
    }
}
