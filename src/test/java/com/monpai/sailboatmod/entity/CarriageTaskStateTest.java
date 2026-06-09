package com.monpai.sailboatmod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarriageTaskStateTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void taskStatePersistsThroughNbt() {
        CompoundTag tag = CarriageEntity.saveLandTaskStateForTest(
                new BlockPos(1, 64, 1),
                new BlockPos(100, 64, 0),
                "town-c",
                false,
                CarriageEntity.TransportTaskKind.DISPATCH
        );

        CarriageEntity.LandTaskSnapshot snapshot = CarriageEntity.loadLandTaskStateForTest(tag);

        assertEquals(new BlockPos(1, 64, 1), snapshot.homeStationPos());
        assertEquals(new BlockPos(100, 64, 0), snapshot.destinationStationPos());
        assertEquals("town-c", snapshot.destinationTownId());
        assertFalse(snapshot.autoReturnOnArrival());
        assertEquals(CarriageEntity.TransportTaskKind.DISPATCH, snapshot.taskKind());
    }

    @Test
    void marketAndDispatchTasksDefaultToAutoReturn() {
        assertTrue(CarriageEntity.defaultAutoReturnForTaskForTest(CarriageEntity.TransportTaskKind.MARKET_ORDER));
        assertTrue(CarriageEntity.defaultAutoReturnForTaskForTest(CarriageEntity.TransportTaskKind.DISPATCH));
    }

    @Test
    void returnAndRecallRoutesDoNotUseTerrainFallback() {
        assertFalse(CarriageEntity.allowTerrainFallbackForLandReturnForTest());
    }

    @Test
    void arrivalFeedbackUsesFiveSecondHoldAndNotice() {
        assertEquals(100, CarriageEntity.arrivalReturnDelayTicksForTest());
        assertEquals(100, CarriageEntity.arrivalNoticeTicksForTest());
        assertTrue(CarriageEntity.arrivalNoticeVisibleForTest(1));
        assertFalse(CarriageEntity.arrivalNoticeVisibleForTest(0));
    }

    @Test
    void autoReturnArrivalHoldSkipsReturnAndRecallTasks() {
        assertTrue(CarriageEntity.shouldDelayReturnAfterArrivalForTest(true, CarriageEntity.TransportTaskKind.DISPATCH, true));
        assertTrue(CarriageEntity.shouldDelayReturnAfterArrivalForTest(true, CarriageEntity.TransportTaskKind.MARKET_ORDER, true));
        assertFalse(CarriageEntity.shouldDelayReturnAfterArrivalForTest(false, CarriageEntity.TransportTaskKind.DISPATCH, true));
        assertFalse(CarriageEntity.shouldDelayReturnAfterArrivalForTest(true, CarriageEntity.TransportTaskKind.RETURN, true));
        assertFalse(CarriageEntity.shouldDelayReturnAfterArrivalForTest(true, CarriageEntity.TransportTaskKind.RECALL, true));
        assertFalse(CarriageEntity.shouldDelayReturnAfterArrivalForTest(true, CarriageEntity.TransportTaskKind.DISPATCH, false));
    }

    @Test
    void arrivalHoldStatePersistsThroughNbt() {
        CompoundTag tag = CarriageEntity.saveArrivalHoldStateForTest(
                new BlockPos(3, 64, 7),
                42,
                77,
                List.of(
                        new Vec3(0.5D, 65.05D, 0.5D),
                        new Vec3(8.5D, 65.05D, 0.5D)
                )
        );

        CarriageEntity.ArrivalHoldSnapshot snapshot = CarriageEntity.loadArrivalHoldStateForTest(tag);

        assertEquals(new BlockPos(3, 64, 7), snapshot.pendingReturnStationPos());
        assertEquals(42, snapshot.pendingReturnDelayTicks());
        assertEquals(77, snapshot.arrivalNoticeTicks());
        assertEquals(2, snapshot.completedRouteWaypoints().size());
    }

    @Test
    void autoReturnCanReuseCompletedOutboundRouteWithoutLoadedHomeStation() {
        List<Vec3> outbound = List.of(
                new Vec3(0.5D, 65.05D, 0.5D),
                new Vec3(20.5D, 65.05D, 0.5D),
                new Vec3(40.5D, 65.05D, 0.5D)
        );

        var route = CarriageEntity.reverseCompletedRouteForReturnForTest(outbound, "Cedar Station");

        assertEquals("Return: Cedar Station", route.name());
        assertEquals(new Vec3(40.5D, 65.05D, 0.5D), route.waypoints().get(0));
        assertEquals(new Vec3(0.5D, 65.05D, 0.5D), route.waypoints().get(route.waypoints().size() - 1));
    }
}
