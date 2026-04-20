package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

public final class RoadTerrainShaper {
    private RoadTerrainShaper() {}

    public record TerrainEdit(BlockPos pos) {}

    public static List<TerrainEdit> shapeRoadbed(
            List<BlockPos> roadbedTop,
            ToIntFunction<BlockPos> heightLookup) {
        if (roadbedTop == null || roadbedTop.isEmpty() || heightLookup == null) {
            return List.of();
        }
        List<TerrainEdit> edits = new ArrayList<>();
        for (BlockPos pos : roadbedTop) {
            int terrainY = heightLookup.applyAsInt(pos);
            if (terrainY > pos.getY()) {
                for (int y = pos.getY() + 1; y <= terrainY; y++) {
                    edits.add(new TerrainEdit(new BlockPos(pos.getX(), y, pos.getZ())));
                }
            }
        }
        return edits;
    }
}
