package com.monpai.sailboatmod.roadplanner.compile;

import com.monpai.sailboatmod.roadplanner.model.RoadToolType;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.UUID;

public record CompiledRoadSection(UUID sourceStrokeId,
                                  CompiledRoadSectionType type,
                                  RoadToolType sourceTool,
                                  List<BlockPos> centerline,
                                  int width) {
    public CompiledRoadSection {
        if (sourceStrokeId == null) {
            sourceStrokeId = new UUID(0L, 0L);
        }
        if (type == null) {
            type = CompiledRoadSectionType.ROAD;
        }
        if (sourceTool == null) {
            sourceTool = RoadToolType.ROAD;
        }
        if (width != 3 && width != 5 && width != 7) {
            throw new IllegalArgumentException("width must be 3, 5, or 7");
        }
        centerline = centerline == null ? List.of() : centerline.stream().map(BlockPos::immutable).toList();
    }
}
