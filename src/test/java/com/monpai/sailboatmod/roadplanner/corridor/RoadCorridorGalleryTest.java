package com.monpai.sailboatmod.roadplanner.corridor;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.roadplanner.structure.RoadNodeExpansionResult;
import com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpander;
import com.monpai.sailboatmod.roadplanner.structure.RoadStructureMode;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Renders generated roads to {@code build/road-evidence} so a change in their shape can be seen, not just asserted. */
class RoadCorridorGalleryTest {
    private static final File OUT = new File("build/road-evidence");

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void rendersARoadAcrossHillsARiverAndARavine() throws Exception {
        SyntheticRoadWorld world = SyntheticRoadWorld.hillsRiverAndRavine();
        List<BlockPos> nodes = List.of(
                new BlockPos(20, 0, -90), new BlockPos(60, 0, -60), new BlockPos(60, 0, -30),
                new BlockPos(110, 0, 30), new BlockPos(170, 0, 40), new BlockPos(215, 0, 85),
                new BlockPos(300, 0, 85), new BlockPos(340, 0, 40));
        List<RoadPlannerSegmentType> types = List.of(
                RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.BRIDGE_MAJOR,
                RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.ROAD,
                RoadPlannerSegmentType.ROAD);
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(nodes, types,
                RoadPlannerBuildSettings.DEFAULTS, world, RoadStructureMode.BUILD);
        world.apply(result.buildSteps());
        RoadWorldRenderer.topDown(world, result.centerline(), 0, -110, 360, 110, 3, new File(OUT, "hills-top.png"));
        RoadWorldRenderer.profile(world, result.centerline(), 4, new File(OUT, "hills-profile.png"));
        RoadWorldRenderer.isometricFromSouthWest(world, 55, -40, 110, 30, 52, 90, 10, new File(OUT, "hills-river-iso.png"));
        RoadWorldRenderer.isometric(world, 225, 60, 300, 105, 50, 90, 10, new File(OUT, "hills-ravine-iso.png"));
        assertTrue(new File(OUT, "hills-top.png").length() > 0);
    }
}
