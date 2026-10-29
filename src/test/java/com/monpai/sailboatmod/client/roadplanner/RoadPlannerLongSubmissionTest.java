package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPreviewRequestPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RoadPlannerLongSubmissionTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test
    void longAndOverflowProneLegsStayCompressedWithoutAnyClientTerrainSweep() throws Exception {
        for (int length : new int[]{40_000, 50_000, 200_000}) {
            List<BlockPos> nodes = List.of(new BlockPos(0, 64, 0), new BlockPos(length, 64, 0));
            AtomicInteger calls = new AtomicInteger();
            RoadPlannerRouteExpander.Result result = expand(nodes, List.of(RoadPlannerSegmentType.BRIDGE_MAJOR),
                    (x, z) -> { calls.incrementAndGet(); return true; }, (x, z) -> { calls.incrementAndGet(); return 64; },
                    (x, z) -> { calls.incrementAndGet(); return 0; });
            assertTrue(result.success());
            assertEquals(nodes, result.nodes());
            assertEquals(List.of(RoadPlannerSegmentType.BRIDGE_MAJOR), result.segmentTypes());
            assertEquals(0, calls.get(), "the GUI must choose staging before interpolating or sampling a distant leg");
        }
    }

    @Test
    void shortSubmissionKeepsTheExistingAutomaticWaterCrossingExpansion() throws Exception {
        var result = expand(List.of(new BlockPos(0, 64, 0), new BlockPos(40, 64, 0)), List.of(RoadPlannerSegmentType.ROAD),
                (x, z) -> x < 12 || x > 28, (x, z) -> 64, (x, z) -> x >= 12 && x <= 28 ? 20 : 0);
        assertTrue(result.success());
        assertTrue(result.segmentTypes().stream().anyMatch(type -> type == RoadPlannerSegmentType.BRIDGE_MAJOR || type == RoadPlannerSegmentType.BRIDGE_SMALL));
    }

    @Test
    void actualGuiSubmitSendsTheCompactFortyThousandBlockRoadBelowTheServerPayloadLimit() throws Exception {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        List<BlockPos> nodes = List.of(new BlockPos(0, 64, 0), new BlockPos(40_000, 64, 0));
        var field = RoadPlannerScreen.class.getDeclaredField("linePlan");
        field.setAccessible(true);
        ((RoadPlannerLinePlan) field.get(screen)).replaceWith(nodes, List.of(RoadPlannerSegmentType.ROAD));
        RoadPlannerGhostPreviewBridge.clearLastPreviewRequestForTest();
        var submit = RoadPlannerScreen.class.getDeclaredMethod("submitPreviewWithSettings", RoadPlannerBuildSettings.class);
        submit.setAccessible(true);
        submit.invoke(screen, RoadPlannerBuildSettings.DEFAULTS);
        var packet = RoadPlannerGhostPreviewBridge.lastPreviewRequestForTest();
        assertNotNull(packet);
        assertEquals(nodes, packet.nodes(), "the real button path must not expand to 5001 client nodes first");
        FriendlyByteBuf bytes = new FriendlyByteBuf(Unpooled.buffer());
        try {
            RoadPlannerPreviewRequestPacket.encode(packet, bytes);
            assertTrue(bytes.readableBytes() < 32_767);
        } finally { bytes.release(); }
    }

    private static RoadPlannerRouteExpander.Result expand(List<BlockPos> nodes, List<RoadPlannerSegmentType> types,
            RoadPlannerBridgeRuleService.LandProbe land, RoadPlannerHeightSampler height, RoadPlannerWaterDepthProbe water) throws Exception {
        try { return (RoadPlannerRouteExpander.Result) RoadPlannerRouteExpander.class.getMethod("expandForSubmission",
                List.class, List.class, RoadPlannerBridgeRuleService.LandProbe.class,
                RoadPlannerHeightSampler.class, RoadPlannerWaterDepthProbe.class).invoke(null, nodes, types, land, height, water); }
        catch (NoSuchMethodException missing) { return fail("the actual GUI needs a bounded submission path before client interpolation"); }
    }
}
