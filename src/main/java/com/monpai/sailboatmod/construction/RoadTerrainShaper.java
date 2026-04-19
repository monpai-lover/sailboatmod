package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Stub class - road system refactored. Pending integration with new road package.
 */
public final class RoadTerrainShaper {
    private RoadTerrainShaper() {}

    public record TerrainEdit(BlockPos pos) {}

    public static List<TerrainEdit> shapeRoadbed(
            List<BlockPos> roadbedTop,
            ToIntFunction<BlockPos> heightLookup) {
        return List.of();
    }
}
