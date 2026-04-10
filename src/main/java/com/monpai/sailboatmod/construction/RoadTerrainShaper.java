package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;

public final class RoadTerrainShaper {
    private RoadTerrainShaper() {
    }

    public static List<TerrainEdit> shapeRoadbed(List<BlockPos> roadbedTop, ToIntFunction<BlockPos> groundY) {
        Objects.requireNonNull(roadbedTop, "roadbedTop");
        Objects.requireNonNull(groundY, "groundY");
        if (roadbedTop.isEmpty()) {
            return List.of();
        }
        ArrayList<TerrainEdit> edits = new ArrayList<>();
        for (BlockPos top : roadbedTop) {
            Objects.requireNonNull(top, "roadbedTop contains null");
            int terrainY = groundY.applyAsInt(top);
            for (int y = terrainY + 1; y < top.getY(); y++) {
                edits.add(new TerrainEdit(new BlockPos(top.getX(), y, top.getZ())));
            }
        }
        return List.copyOf(edits);
    }

    public record TerrainEdit(BlockPos pos) {
        public TerrainEdit {
            pos = Objects.requireNonNull(pos, "pos").immutable();
        }
    }
}
