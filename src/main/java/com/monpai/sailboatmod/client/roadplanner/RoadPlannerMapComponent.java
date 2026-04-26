package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.map.RoadMapPoint;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRegion;
import com.monpai.sailboatmod.roadplanner.map.RoadMapViewport;
import net.minecraft.core.BlockPos;

import java.util.Objects;

public class RoadPlannerMapComponent {
    private final RoadMapRegion region;
    private final RoadMapViewport viewport;

    public RoadPlannerMapComponent(RoadMapRegion region, RoadMapViewport viewport) {
        this.region = Objects.requireNonNull(region, "region");
        this.viewport = Objects.requireNonNull(viewport, "viewport");
    }

    public BlockPos guiToWorldXZ(double guiX, double guiY) {
        return region.guiToWorldXZ(guiX, guiY, viewport);
    }

    public RoadMapPoint worldToGui(BlockPos worldPos) {
        return region.worldToGui(worldPos, viewport);
    }
}
