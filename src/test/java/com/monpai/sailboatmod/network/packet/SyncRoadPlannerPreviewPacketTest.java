package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.construction.RoadCorridorPlan;
import com.monpai.sailboatmod.construction.RoadGeometryPlanner;
import com.monpai.sailboatmod.construction.RoadPlacementPlan;
import com.monpai.sailboatmod.nation.service.ManualRoadPlannerService;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncRoadPlannerPreviewPacketTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void roundTripsFullPreviewPayload() {
        SyncRoadPlannerPreviewPacket packet = new SyncRoadPlannerPreviewPacket(
                "SourceTown",
                "TargetTown",
                java.util.List.of(new SyncRoadPlannerPreviewPacket.GhostBlock(
                        new BlockPos(2, 65, 2),
                        Blocks.STONE_BRICK_SLAB.defaultBlockState()
                )),
                7,
                new BlockPos(1, 65, 1),
                new BlockPos(8, 65, 8),
                new BlockPos(5, 65, 5),
                true
        );

        FriendlyByteBuf encoded = assertDoesNotThrow(() -> encode(packet));
        SyncRoadPlannerPreviewPacket decoded = assertDoesNotThrow(() -> SyncRoadPlannerPreviewPacket.decode(copy(encoded)));
        FriendlyByteBuf roundTrip = assertDoesNotThrow(() -> encode(decoded));

        assertArrayEquals(toByteArray(encoded), toByteArray(roundTrip));
    }

    @Test
    void roundTripsEmptyPreviewPayloadWithClearedHighlights() {
        SyncRoadPlannerPreviewPacket packet = new SyncRoadPlannerPreviewPacket(
                "",
                "",
                java.util.List.of(),
                0,
                null,
                null,
                null,
                false
        );

        FriendlyByteBuf encoded = assertDoesNotThrow(() -> encode(packet));
        SyncRoadPlannerPreviewPacket decoded = assertDoesNotThrow(() -> SyncRoadPlannerPreviewPacket.decode(copy(encoded)));
        FriendlyByteBuf roundTrip = assertDoesNotThrow(() -> encode(decoded));

        assertArrayEquals(toByteArray(encoded), toByteArray(roundTrip));
    }

    @Test
    void trimsOverlongTownNamesBeforeEncodingPreviewPayload() {
        String longName = "A".repeat(80);
        SyncRoadPlannerPreviewPacket packet = new SyncRoadPlannerPreviewPacket(
                longName,
                longName,
                java.util.List.of(new SyncRoadPlannerPreviewPacket.GhostBlock(
                        new BlockPos(2, 65, 2),
                        Blocks.STONE_BRICK_SLAB.defaultBlockState()
                )),
                9,
                new BlockPos(1, 65, 1),
                new BlockPos(8, 65, 8),
                new BlockPos(5, 65, 5),
                true
        );

        FriendlyByteBuf encoded = assertDoesNotThrow(() -> encode(packet));
        SyncRoadPlannerPreviewPacket decoded = assertDoesNotThrow(() -> SyncRoadPlannerPreviewPacket.decode(copy(encoded)));

        assertEquals(64, decoded.sourceTownName().length());
        assertEquals(64, decoded.targetTownName().length());
    }

    @Test
    void previewFingerprintChangesWhenPlanDetailsChangeWithoutPathChange() {
        List<BlockPos> centerPath = List.of(new BlockPos(1, 64, 1), new BlockPos(2, 64, 1));
        RoadPlacementPlan firstPlan = new RoadPlacementPlan(
                centerPath,
                new BlockPos(0, 64, 1),
                centerPath.get(0),
                centerPath.get(1),
                new BlockPos(3, 64, 1),
                List.of(new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(1, 65, 1), Blocks.STONE_BRICK_SLAB.defaultBlockState())),
                List.of(new RoadGeometryPlanner.RoadBuildStep(0, new BlockPos(1, 65, 1), Blocks.STONE_BRICK_SLAB.defaultBlockState())),
                List.of(),
                centerPath.get(0).above(),
                centerPath.get(1).above(),
                new BlockPos(1, 65, 1)
        );
        RoadPlacementPlan secondPlan = new RoadPlacementPlan(
                centerPath,
                new BlockPos(0, 64, 1),
                centerPath.get(0),
                centerPath.get(1),
                new BlockPos(3, 64, 1),
                List.of(new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(1, 65, 1), Blocks.SMOOTH_SANDSTONE_SLAB.defaultBlockState())),
                List.of(new RoadGeometryPlanner.RoadBuildStep(0, new BlockPos(1, 65, 1), Blocks.SMOOTH_SANDSTONE_SLAB.defaultBlockState())),
                List.of(),
                centerPath.get(0).above(),
                centerPath.get(1).above(),
                new BlockPos(1, 65, 1)
        );

        assertNotEquals(
                ManualRoadPlannerService.previewHashForTest(firstPlan),
                ManualRoadPlannerService.previewHashForTest(secondPlan)
        );
    }

    @Test
    void fromPlanUsesPlacementGhostBlocksForFullBridgePreviewPayload() {
        RoadPlacementPlan plan = bridgePreviewPlanFixture();

        SyncRoadPlannerPreviewPacket packet = SyncRoadPlannerPreviewPacket.fromPlan(
                "SourceTown",
                "TargetTown",
                plan,
                true
        );

        assertEquals(
                plan.ghostBlocks().stream()
                        .map(block -> new SyncRoadPlannerPreviewPacket.GhostBlock(block.pos(), block.state()))
                        .toList(),
                packet.ghostBlocks()
        );
        assertTrue(packet.ghostBlocks().stream().anyMatch(block -> block.pos().equals(new BlockPos(2, 70, 0))));
        assertTrue(packet.ghostBlocks().stream().anyMatch(block -> block.pos().equals(new BlockPos(1, 61, 0))));
        assertEquals(plan.centerPath().size(), packet.pathNodeCount());
    }

    private static FriendlyByteBuf encode(SyncRoadPlannerPreviewPacket packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        SyncRoadPlannerPreviewPacket.encode(packet, buffer);
        return buffer;
    }

    private static FriendlyByteBuf copy(FriendlyByteBuf buffer) {
        return new FriendlyByteBuf(buffer.copy());
    }

    private static byte[] toByteArray(FriendlyByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(0, bytes);
        return bytes;
    }

    private static RoadPlacementPlan bridgePreviewPlanFixture() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0)
        );
        List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks = List.of(
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 65, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(1, 66, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(2, 70, 0), Blocks.STONE_BRICK_STAIRS.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(1, 62, 0), Blocks.STONE_BRICKS.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(1, 61, 0), Blocks.LANTERN.defaultBlockState())
        );
        return new RoadPlacementPlan(
                centerPath,
                centerPath.get(0),
                centerPath.get(0),
                centerPath.get(centerPath.size() - 1),
                centerPath.get(centerPath.size() - 1),
                ghostBlocks,
                List.of(
                        new RoadGeometryPlanner.RoadBuildStep(0, new BlockPos(0, 65, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(1, new BlockPos(1, 66, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(2, new BlockPos(2, 70, 0), Blocks.STONE_BRICK_STAIRS.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(3, new BlockPos(1, 62, 0), Blocks.STONE_BRICKS.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(4, new BlockPos(1, 61, 0), Blocks.LANTERN.defaultBlockState())
                ),
                List.of(new RoadPlacementPlan.BridgeRange(1, 1)),
                List.of(new RoadPlacementPlan.BridgeRange(1, 1)),
                ghostBlocks.stream().map(RoadGeometryPlanner.GhostRoadBlock::pos).toList(),
                centerPath.get(0).above(),
                centerPath.get(centerPath.size() - 1).above(),
                new BlockPos(1, 66, 0),
                new RoadCorridorPlan(
                        centerPath,
                        List.of(
                                new RoadCorridorPlan.CorridorSlice(
                                        0,
                                        new BlockPos(0, 65, 0),
                                        RoadCorridorPlan.SegmentKind.LAND_APPROACH,
                                        List.of(new BlockPos(0, 65, 0)),
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        List.of()
                                ),
                                new RoadCorridorPlan.CorridorSlice(
                                        1,
                                        new BlockPos(1, 66, 0),
                                        RoadCorridorPlan.SegmentKind.NAVIGABLE_MAIN_SPAN,
                                        List.of(new BlockPos(1, 66, 0)),
                                        List.of(),
                                        List.of(),
                                        List.of(new BlockPos(1, 62, 0)),
                                        List.of(new BlockPos(1, 61, 0))
                                ),
                                new RoadCorridorPlan.CorridorSlice(
                                        2,
                                        new BlockPos(2, 70, 0),
                                        RoadCorridorPlan.SegmentKind.LAND_APPROACH,
                                        List.of(new BlockPos(2, 70, 0)),
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        List.of()
                                )
                        ),
                        new RoadCorridorPlan.NavigationChannel(new BlockPos(1, 61, 0), new BlockPos(1, 65, 0)),
                        true
                )
        );
    }
}
