package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.road.config.BridgeConfig;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoadPlannerBridgeWindowTest {
    @Test
    void slicedLongBridgeRetainsTheOriginalDeckRampsAndGlobalPierSpacing() throws Exception {
        List<RoadCenterlinePoint> points = new ArrayList<>();
        for (int x = 0; x <= 400; x++) points.add(new RoadCenterlinePoint(new BlockPos(x, 64, 0), 0,
                RoadPlannerSegmentType.BRIDGE_MAJOR, 64, 64, x));
        RoadTerrainSampler sampler = new RoadTerrainSampler() {
            @Override public int terrainY(int x, int z) { return 64; }
            @Override public int waterSurfaceY(int x, int z) { return 63; }
            @Override public int oceanFloorY(int x, int z) { return 40; }
            @Override public boolean isWater(int x, int y, int z) { return y == 63; }
        };
        RoadSpan wholeSpan = new RoadSpan(RoadSpanType.BRIDGE, 0, 400, RoadPlannerSegmentType.BRIDGE_MAJOR);
        var whole = RoadPlannerBridgeGeometryPlanner.plan(points, wholeSpan, sampler, new BridgeConfig());
        for (int offset : new int[]{0, 128, 384}) {
            int end = Math.min(401, offset + 128);
            List<RoadCenterlinePoint> slice = points.subList(offset, end);
            RoadPlannerBridgeGeometryPlanner.Plan window;
            try {
                window = (RoadPlannerBridgeGeometryPlanner.Plan) RoadPlannerBridgeGeometryPlanner.class.getMethod("planWindow",
                        List.class, RoadSpan.class, RoadTerrainSampler.class, BridgeConfig.class,
                        int.class, int.class, int.class, int.class, int.class).invoke(null,
                        slice, wholeSpan, sampler, new BridgeConfig(), offset, 400, 64, 64, 63);
            } catch (NoSuchMethodException missing) {
                window = fail("bridge windows must use the original global profile, not create a new shore/ramp in every stage");
            }
            assertEquals(whole.deckY(), window.deckY());
            for (int i = 0; i < slice.size(); i++) {
                assertEquals(whole.points().get(offset + i).point().targetY(), window.points().get(i).point().targetY());
                assertEquals(whole.points().get(offset + i).phase(), window.points().get(i).phase());
            }
            assertEquals(whole.piers().stream().filter(p -> p.center().getX() >= offset && p.center().getX() < end).toList(), window.piers());
        }
    }
}
