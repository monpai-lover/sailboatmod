package com.monpai.sailboatmod.roadplanner.corridor;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.pathfinding.PathResult;
import com.monpai.sailboatmod.road.pathfinding.PathfinderFactory;
import com.monpai.sailboatmod.route.PathSmoother;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the real road search between two towns and renders the route before and after refinement. */
class RoadRouteGalleryTest {
    private static final File OUT = new File("build/road-evidence");

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static int[] route(int rgb, List<BlockPos> nodes) {
        int[] out = new int[1 + nodes.size() * 2];
        out[0] = rgb;
        for (int i = 0; i < nodes.size(); i++) {
            out[1 + i * 2] = nodes.get(i).getX();
            out[2 + i * 2] = nodes.get(i).getZ();
        }
        return out;
    }

    @Test
    void rendersASearchedRouteBeforeAndAfterRefinementAndAsBuilt() throws Exception {
        SyntheticRoadWorld world = SyntheticRoadWorld.coastalCountry();
        SyntheticRouteTerrain terrain = new SyntheticRouteTerrain(world);
        BlockPos from = new BlockPos(180, 70, 20);
        BlockPos to = new BlockPos(200, 70, 680);
        PathResult searched = PathfinderFactory.create(new PathfindingConfig()).findPath(from, to, terrain);
        assertTrue(searched.success(), searched.failureReason());

        List<BlockPos> before = PathSmoother.smooth2DInterpolatedY(searched.path(), 24);
        List<BlockPos> after = RoadRouteRefiner.refine(searched.path(), 24, terrain);
        RoadWorldRenderer.topDownWithRoutes(world, 0, 0, 400, 700, 2, new File(OUT, "route-before.png"), route(0xFFE000, before));
        RoadWorldRenderer.topDownWithRoutes(world, 0, 0, 400, 700, 2, new File(OUT, "route-after.png"), route(0xFFE000, after));

        List<RoadPlannerSegmentType> types = new ArrayList<>();
        for (int i = 1; i < after.size(); i++) {
            types.add(RoadPlannerSegmentType.ROAD);
        }
        RoadCorridorCompiler.Result built = RoadCorridorCompiler.compile(RoadAlignment.of(after, types),
                RoadPlannerBuildSettings.DEFAULTS, world);
        world.apply(built.steps());
        RoadWorldRenderer.topDown(world, built.centerline(), 0, 0, 400, 700, 2, new File(OUT, "route-built.png"));
        RoadWorldRenderer.profile(world, built.centerline(), 2, new File(OUT, "route-profile.png"));
    }
}
