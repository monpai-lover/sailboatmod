package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.construction.RoadGeometryPlanner;
import com.monpai.sailboatmod.construction.RoadPlacementPlan;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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

        assertNotEquals(invokePreviewFingerprint(firstPlan), invokePreviewFingerprint(secondPlan));
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

    private static String invokePreviewFingerprint(RoadPlacementPlan plan) {
        try {
            Method method = Class
                    .forName("com.monpai.sailboatmod.nation.service.ManualRoadPlannerService")
                    .getDeclaredMethod("previewHash", RoadPlacementPlan.class);
            method.setAccessible(true);
            return (String) method.invoke(null, plan);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect road preview fingerprinting", ex);
        }
    }
}
