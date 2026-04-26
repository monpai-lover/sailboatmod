package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.map.RoadMapRegion;
import com.monpai.sailboatmod.roadplanner.map.RoadMapViewport;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class RoadStrokeSampler {
    private final RoadMapRegion region;
    private final RoadMapViewport viewport;
    private final HeightSampler heightSampler;

    public RoadStrokeSampler(RoadMapRegion region, RoadMapViewport viewport, HeightSampler heightSampler) {
        this.region = Objects.requireNonNull(region, "region");
        this.viewport = Objects.requireNonNull(viewport, "viewport");
        this.heightSampler = Objects.requireNonNull(heightSampler, "heightSampler");
    }

    public List<BlockPos> sampleDrag(double startGuiX,
                                     double startGuiY,
                                     double endGuiX,
                                     double endGuiY,
                                     int minSpacingBlocks,
                                     int maxSpacingBlocks) {
        BlockPos start = withHeight(region.guiToWorldXZ(startGuiX, startGuiY, viewport));
        BlockPos end = withHeight(region.guiToWorldXZ(endGuiX, endGuiY, viewport));
        double dx = end.getX() - start.getX();
        double dz = end.getZ() - start.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        int spacing = Math.max(1, Math.min(Math.max(minSpacingBlocks, 1), Math.max(maxSpacingBlocks, 1)));
        int segments = Math.max(1, (int) Math.ceil(distance / spacing));

        List<BlockPos> nodes = new ArrayList<>(segments + 1);
        for (int index = 0; index <= segments; index++) {
            double t = index / (double) segments;
            int x = (int) Math.round(start.getX() + dx * t);
            int z = (int) Math.round(start.getZ() + dz * t);
            nodes.add(new BlockPos(x, heightSampler.heightAt(x, z), z));
        }
        return List.copyOf(nodes);
    }

    private BlockPos withHeight(BlockPos pos) {
        return new BlockPos(pos.getX(), heightSampler.heightAt(pos.getX(), pos.getZ()), pos.getZ());
    }

    @FunctionalInterface
    public interface HeightSampler {
        int heightAt(int worldX, int worldZ);
    }
}
