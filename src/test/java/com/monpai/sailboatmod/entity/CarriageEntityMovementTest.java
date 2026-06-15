package com.monpai.sailboatmod.entity;

import com.monpai.sailboatmod.route.CarriageRoutePlan;
import com.monpai.sailboatmod.route.RouteDefinition;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarriageEntityMovementTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void carriageIsAStandaloneLandVehicleInsteadOfABoatSubtype() {
        assertFalse(Boat.class.isAssignableFrom(CarriageEntity.class));
        assertFalse(SailboatEntity.class.isAssignableFrom(CarriageEntity.class));
    }

    @Test
    void recognizesFinishedRoadLikeSurfaces() {
        assertTrue(CarriageEntity.isRoadSurfaceForTest(Blocks.STONE_BRICKS.defaultBlockState()));
        assertTrue(CarriageEntity.isRoadSurfaceForTest(Blocks.STONE_BRICK_SLAB.defaultBlockState()));
        assertTrue(CarriageEntity.isRoadSurfaceForTest(Blocks.STONE_BRICK_STAIRS.defaultBlockState()));
        assertFalse(CarriageEntity.isRoadSurfaceForTest(Blocks.DIRT.defaultBlockState()));
    }

    @Test
    void carriageDoesNotNeedVanillaBoatMovementBypassAfterLeavingBoatHierarchy() {
        assertFalse(CarriageEntity.skipsVanillaBoatMovementTickForTest());
    }

    @Test
    void carriageDoesNotAcceptVanillaBoatInputForManualDriving() {
        assertFalse(CarriageEntity.acceptsVanillaBoatInputForManualDriveForTest());
    }

    @Test
    void carriageMovementIsServerAuthoritativeInsteadOfClientBoatControlled() {
        assertTrue(CarriageEntity.usesServerAuthoritativeRiderMovementForTest());
    }

    @Test
    void autopilotYieldsLocalInstanceControlBackToServerEvenWithRider() {
        // 自动驾驶时载具必须交还服务端权威，否则玩家作为乘客会让客户端把它当本地控制，
        // 服务端权威位置不被同步 → 车有动画/特效却原地不动（带不动玩家）。
        assertFalse(CarriageEntity.isLocalInstanceControlAuthoritativeForTest(true),
                "autopilot must not be treated as client-local-controlled (server is authoritative)");
        // 手动驾驶时保留客户端本地控制（保持客户端预测手感）。
        assertTrue(CarriageEntity.isLocalInstanceControlAuthoritativeForTest(false),
                "manual driving should keep client-local control for prediction");
    }

    @Test
    void carriageStillPredictsLandDriveOnClientForLocalRider() {
        assertTrue(CarriageEntity.simulatesLandDriveOnClientForTest());
    }

    @Test
    void carriageAppliesClientInputLocallyBeforeServerEcho() {
        assertTrue(CarriageEntity.appliesClientInputLocallyForTest());
    }

    @Test
    void carriageUsesCrayfishStyleNetworkLerpForRemoteUpdates() {
        assertEquals(10, CarriageEntity.networkLerpStepsForTest(3));
        assertEquals(0, CarriageEntity.lerpStepsAfterLocalControlForTest(10));
    }

    @Test
    void carriageUsesImmediateNetworkLerpForAutopilotRailPose() {
        assertEquals(1, CarriageEntity.networkLerpStepsForAutopilotForTest(3));
    }

    @Test
    void carriageKeepsServerAutopilotLerpForLocalRider() {
        assertEquals(1, CarriageEntity.lerpStepsAfterLocalControlForTest(1, true));
        assertEquals(0, CarriageEntity.lerpStepsAfterLocalControlForTest(10, false));
    }

    @Test
    void carriageDoesNotApplyClientLandDriveWhileAutopilotIsNetworkAuthoritative() {
        assertTrue(CarriageEntity.shouldRunClientLandDriveForTest(false));
        assertFalse(CarriageEntity.shouldRunClientLandDriveForTest(true));
    }

    @Test
    void staleSeatAssignmentsDoNotBlockDriverSeatAfterRemount() {
        UUID stalePassenger = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID remountingPlayer = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Map<UUID, Integer> assignments = new HashMap<>();
        assignments.put(stalePassenger, 0);

        int seat = CarriageEntity.chooseBoardingSeatForTest(assignments, Set.of(), remountingPlayer);

        assertEquals(0, seat);
        assertFalse(assignments.containsKey(stalePassenger));
    }

    @Test
    void carriageDoesNotUseSailboatClientTickForNetworkInterpolation() {
        assertFalse(CarriageEntity.usesSailboatClientTickForTest());
    }

    @Test
    void carriageDoesNotUseBoatFluidSupport() {
        assertFalse(CarriageEntity.canUseBoatFluidSupportForTest());
    }

    @Test
    void carriageGroundSupportAcceptsPartialCollisionRoadBlocks() {
        assertTrue(CarriageEntity.isDriveableGroundStateForTest(Blocks.STONE_BRICK_SLAB.defaultBlockState()));
        assertTrue(CarriageEntity.isDriveableGroundStateForTest(Blocks.STONE_BRICK_STAIRS.defaultBlockState()));
        assertFalse(CarriageEntity.isDriveableGroundStateForTest(Blocks.WATER.defaultBlockState()));
        assertFalse(CarriageEntity.isDriveableGroundStateForTest(Blocks.AIR.defaultBlockState()));
    }

    @Test
    void railAutopilotRideHeightUsesRoadBlockCollisionTop() {
        BlockPos surface = new BlockPos(0, 64, 0);

        assertEquals(65.05D, CarriageEntity.railAutopilotRideYForSupportForTest(
                Blocks.STONE_BRICKS.defaultBlockState(),
                surface,
                0.5D,
                0.5D
        ), 1.0E-6D);
        assertEquals(64.55D, CarriageEntity.railAutopilotRideYForSupportForTest(
                Blocks.STONE_BRICK_SLAB.defaultBlockState(),
                surface,
                0.5D,
                0.5D
        ), 1.0E-6D);
        assertTrue(CarriageEntity.railAutopilotRideYForSupportForTest(
                Blocks.STONE_BRICK_STAIRS.defaultBlockState(),
                surface,
                0.5D,
                0.5D
        ) >= 64.55D);
    }

    @Test
    void railAutopilotSurfaceSnapKeepsBuriedRoutePointsOnRoadSurface() {
        Vec3 lowRoutePoint = new Vec3(12.5D, 58.0D, 20.5D);

        Vec3 corrected = CarriageEntity.railAutopilotSurfacePositionForTest(lowRoutePoint, 65.05D);

        assertEquals(12.5D, corrected.x, 1.0E-6D);
        assertEquals(65.05D, corrected.y, 1.0E-6D);
        assertEquals(20.5D, corrected.z, 1.0E-6D);
    }

    @Test
    void railAutopilotDeltaUsesSurfaceCorrectedYInsteadOfRouteY() {
        Vec3 current = new Vec3(10.5D, 65.05D, 20.5D);
        Vec3 lowRoutePoint = new Vec3(12.5D, 58.0D, 20.5D);

        Vec3 delta = CarriageEntity.railAutopilotDeltaForCorrectedSurfaceForTest(current, lowRoutePoint, 65.05D);

        assertEquals(2.0D, delta.x, 1.0E-6D);
        assertEquals(0.0D, delta.y, 1.0E-6D);
        assertEquals(0.0D, delta.z, 1.0E-6D);
    }

    @Test
    void railAutopilotUsesSurfaceValidatedDirectPoseSoStepsDoNotCancelMovement() {
        assertFalse(CarriageEntity.usesEntityMoveForRailAutopilotForTest());
    }

    @Test
    void railAutopilotStillSyncsPassengersAfterDirectPoseMove() {
        assertTrue(CarriageEntity.syncsPassengersAfterRailAutopilotPoseForTest());
    }

    @Test
    void railAutopilotSpeedUsesRoutePoseDeltaInsteadOfCollisionClippedMotion() {
        assertEquals(7.2F, CarriageEntity.railAutopilotCurrentSpeedForDeltaForTest(
                new Vec3(0.36D, 0.0D, 0.0D)
        ), 1.0E-5F);
    }

    @Test
    void railAutopilotHardSnapsBackToRoadSurfaceWhenCollisionLeavesItBuried() {
        Vec3 buriedStart = new Vec3(10.5D, 58.0D, 20.5D);
        Vec3 roadSurfaceTarget = new Vec3(10.9D, 65.05D, 20.5D);
        Vec3 blockedAfterMove = new Vec3(10.6D, 58.0D, 20.5D);

        assertTrue(CarriageEntity.shouldHardSnapRailAutopilotForTest(buriedStart, roadSurfaceTarget, blockedAfterMove));
        assertFalse(CarriageEntity.shouldHardSnapRailAutopilotForTest(
                buriedStart,
                roadSurfaceTarget,
                new Vec3(10.9D, 65.05D, 20.5D)
        ));
    }

    @Test
    void carriageUsesLandVehicleStepHeight() {
        assertTrue(CarriageEntity.landVehicleStepHeightForTest() >= 1.0F);
    }

    @Test
    void carriageGroundSupportUsesDrawnVehicleSolverInsteadOfDirectForwardBonus() {
        Vec3 next = CarriageEntity.solveGroundMotionForTest(
                new Vec3(0.4D, 0.0D, 0.6D),
                0.0F,
                25.0F,
                SailboatEntity.EngineGear.FULL_AHEAD,
                true,
                true,
                false,
                false
        );

        assertTrue(next.z > next.x, "motion should align toward the virtual horse heading instead of preserving sideways glide");
    }

    @Test
    void carriageTickRetainsLandDriveHorizontalMotionAsEntityVelocity() {
        Vec3 applied = CarriageEntity.appliedLandDriveMotionForTest(
                new Vec3(0.0D, -0.02D, 0.0D),
                new Vec3(0.12D, -0.08D, 0.24D)
        );

        assertEquals(0.12D, applied.x, 1.0E-6D);
        assertEquals(-0.08D, applied.y, 1.0E-6D);
        assertEquals(0.24D, applied.z, 1.0E-6D);
    }

    @Test
    void carriageHudSpeedUsesSyncedCurrentSpeed() {
        assertEquals(3.5F, CarriageEntity.currentSpeedForHudForTest(0.0F, 3.5F), 1.0E-6F);
    }

    @Test
    void carriageReverseUsesLowSpeedTractionCurve() {
        double reverseTarget = CarriageEntity.targetSpeedForTest(SailboatEntity.EngineGear.FULL_ASTERN);

        assertTrue(reverseTarget < 0.0D);
        assertTrue(Math.abs(reverseTarget) <= VirtualHorseDriveState.MAX_REVERSE_SPEED);
    }

    @Test
    void carriageAutopilotUsesCruiseGearOnTerrainConnectorSegments() {
        CarriageRoutePlan.Segment segment = new CarriageRoutePlan.Segment(
                CarriageRoutePlan.SegmentKind.TERRAIN_CONNECTOR,
                java.util.List.of(new net.minecraft.core.BlockPos(0, 64, 0), new net.minecraft.core.BlockPos(1, 64, 0))
        );

        // 非道路（首尾段）巡航也用 TWO_THIRDS，避免离开道路时慢到几乎不动；减速由接近目标/大转向处理。
        assertTrue(CarriageEntity.autopilotGearForSegmentForTest(segment) == SailboatEntity.EngineGear.TWO_THIRDS_AHEAD);
    }

    @Test
    void carriageAutopilotSkipsDenseRoadWaypointsAlreadyInsideCaptureRadius() {
        List<Vec3> route = List.of(
                new Vec3(0.5D, 65.05D, 0.5D),
                new Vec3(1.5D, 65.05D, 0.5D),
                new Vec3(2.5D, 65.05D, 0.5D),
                new Vec3(3.5D, 65.05D, 0.5D),
                new Vec3(4.5D, 65.05D, 0.5D),
                new Vec3(5.5D, 65.05D, 0.5D),
                new Vec3(6.5D, 65.05D, 0.5D),
                new Vec3(7.5D, 65.05D, 0.5D),
                new Vec3(8.5D, 65.05D, 0.5D)
        );

        int next = CarriageEntity.advanceAutopilotTargetIndexForTest(route, new Vec3(3.5D, 65.05D, 0.5D), 1);

        assertEquals(7, next);
    }

    @Test
    void carriageAutopilotDoesNotCommandStopForLargeHeadingMismatchBecauseItCannotPivotInPlace() {
        SailboatEntity.EngineGear gear = CarriageEntity.autopilotGearForHeadingForTest(false, 24.0D, 120.0F);

        assertEquals(SailboatEntity.EngineGear.ONE_THIRD_AHEAD, gear);
    }

    @Test
    void carriageAutopilotTurnsLeftTowardEastRoadPointWhenFacingSouth() {
        CarriageDriveInput.TurnDirection direction = CarriageEntity.autopilotTurnDirectionForTargetForTest(
                new Vec3(0.5D, 65.05D, 0.5D),
                0.0F,
                new Vec3(4.5D, 65.05D, 0.5D)
        );

        assertEquals(CarriageDriveInput.TurnDirection.LEFT, direction);
    }

    @Test
    void carriageAutopilotTurnsRightTowardWestRoadPointWhenFacingSouth() {
        CarriageDriveInput.TurnDirection direction = CarriageEntity.autopilotTurnDirectionForTargetForTest(
                new Vec3(0.5D, 65.05D, 0.5D),
                0.0F,
                new Vec3(-3.5D, 65.05D, 0.5D)
        );

        assertEquals(CarriageDriveInput.TurnDirection.RIGHT, direction);
    }

    @Test
    void carriageAutopilotRailStepSnapsBackToRouteCenterline() {
        CarriageRailPathFollower.StepResult result = CarriageEntity.autopilotRailStepForTest(
                List.of(
                        new Vec3(0.5D, 65.05D, 0.5D),
                        new Vec3(1.5D, 65.05D, 0.5D),
                        new Vec3(2.5D, 65.05D, 0.5D)
                ),
                new Vec3(0.5D, 65.05D, 1.4D),
                1
        );

        assertTrue(result.active());
        assertEquals(0.5D, result.position().z, 1.0E-6D);
    }

    @Test
    void carriageAutopilotStartValidationUsesAssignedStationBeforeFirstWaypoint() {
        RouteDefinition roadRoute = new RouteDefinition(
                "station-road",
                List.of(
                        new Vec3(32.5D, 65.05D, 0.5D),
                        new Vec3(48.5D, 65.05D, 0.5D)
                )
        );

        Vec3 anchor = CarriageEntity.routeStartValidationPointForTest(roadRoute, new BlockPos(0, 64, 0));

        assertEquals(Vec3.atCenterOf(new BlockPos(0, 64, 0)), anchor);
    }

    @Test
    void carriageAutopilotPrependsCurrentParkingPointSoDispatchCanLeaveStationZone() {
        Vec3 currentParkingPoint = new Vec3(7.5D, 65.05D, 4.5D);
        List<Vec3> stationRoadRoute = List.of(
                new Vec3(0.5D, 65.05D, 0.5D),
                new Vec3(16.5D, 65.05D, 0.5D),
                new Vec3(32.5D, 65.05D, 0.5D)
        );

        List<Vec3> runtimeRoute = CarriageEntity.autopilotRouteWithCurrentStartForTest(stationRoadRoute, currentParkingPoint);
        CarriageRailPathFollower.StepResult firstStep = CarriageRailPathFollower.step(
                runtimeRoute,
                currentParkingPoint,
                1,
                0.36D
        );

        assertEquals(currentParkingPoint, runtimeRoute.get(0));
        assertEquals(stationRoadRoute.get(0), runtimeRoute.get(1));
        assertTrue(firstStep.active());
    }

    @Test
    void carriageAutopilotSkipsStartStationBlockWhenLeavingStationZone() {
        BlockPos stationPos = new BlockPos(0, 64, 0);
        Vec3 currentParkingPoint = new Vec3(7.5D, 65.05D, 4.5D);
        Vec3 stationBlockWaypoint = new Vec3(0.5D, 65.05D, 0.5D);
        Vec3 firstRoadWaypoint = new Vec3(16.5D, 65.05D, 0.5D);
        List<Vec3> dispatchRoute = List.of(
                stationBlockWaypoint,
                firstRoadWaypoint,
                new Vec3(32.5D, 65.05D, 0.5D)
        );

        List<Vec3> runtimeRoute = CarriageEntity.autopilotRouteWithCurrentStartForTest(
                dispatchRoute,
                currentParkingPoint,
                stationPos
        );

        assertEquals(currentParkingPoint, runtimeRoute.get(0));
        assertEquals(firstRoadWaypoint, runtimeRoute.get(1));
        assertFalse(runtimeRoute.contains(stationBlockWaypoint));
    }

    @Test
    void carriageAutopilotDoesNotTeleportStationDepartureAwayFromCurrentParkingPoint() {
        Vec3 currentParkingPoint = new Vec3(7.5D, 65.05D, 4.5D);
        List<Vec3> runtimeRoute = List.of(
                currentParkingPoint,
                new Vec3(16.5D, 65.05D, 0.5D),
                new Vec3(32.5D, 65.05D, 0.5D)
        );

        Vec3 snapped = CarriageEntity.railAutopilotStartPositionForTest(runtimeRoute, currentParkingPoint, new BlockPos(0, 64, 0));

        assertEquals(currentParkingPoint.x, snapped.x, 1.0E-6D);
        assertEquals(65.05D, snapped.y, 1.0E-6D);
        assertEquals(currentParkingPoint.z, snapped.z, 1.0E-6D);
    }

    @Test
    void carriageAutopilotReplacesSameColumnLowStartWaypointWithCurrentHeight() {
        Vec3 currentParkingPoint = new Vec3(7.5D, 65.05D, 4.5D);
        Vec3 buriedStartWaypoint = new Vec3(7.5D, 58.0D, 4.5D);
        Vec3 firstRoadWaypoint = new Vec3(16.5D, 65.05D, 0.5D);

        List<Vec3> runtimeRoute = CarriageEntity.autopilotRouteWithCurrentStartForTest(
                List.of(buriedStartWaypoint, firstRoadWaypoint),
                currentParkingPoint,
                new BlockPos(0, 64, 0)
        );
        Vec3 snapped = CarriageEntity.railAutopilotStartPositionForTest(runtimeRoute, currentParkingPoint, new BlockPos(0, 64, 0));
        CarriageRailPathFollower.StepResult firstStep = CarriageRailPathFollower.step(runtimeRoute, currentParkingPoint, 1, 0.36D);

        assertEquals(currentParkingPoint, runtimeRoute.get(0));
        assertFalse(runtimeRoute.contains(buriedStartWaypoint));
        assertEquals(currentParkingPoint.y, snapped.y, 1.0E-6D);
        assertTrue(firstStep.active());
        assertTrue(firstStep.position().y > 64.0D);
    }

    @Test
    void railAutopilotYawMatchesActualMovementDirection() {
        float yaw = CarriageEntity.railAutopilotPoseYawForTest(
                90.0F,
                0.0F,
                new Vec3(0.36D, 0.0D, 0.0D)
        );

        assertEquals(-90.0F, yaw, 1.0E-4F);
    }

    @Test
    void carriageMovementSoundStaysSilentWhenStopped() {
        CarriageEntity.MovementSoundCue cue = CarriageEntity.movementSoundCueForTest(0.0F, 0);

        assertFalse(cue.play());
        assertEquals(0, cue.nextCooldownTicks());
    }

    @Test
    void carriageMovementSoundWaitsForCooldownBeforePlayingAgain() {
        CarriageEntity.MovementSoundCue cue = CarriageEntity.movementSoundCueForTest(4.0F, 5);

        assertFalse(cue.play());
        assertEquals(4, cue.nextCooldownTicks());
    }

    @Test
    void carriageMovementSoundScalesPitchVolumeAndIntervalWithSpeed() {
        CarriageEntity.MovementSoundCue slow = CarriageEntity.movementSoundCueForTest(1.5F, 0);
        CarriageEntity.MovementSoundCue fast = CarriageEntity.movementSoundCueForTest(8.0F, 0);

        assertTrue(slow.play());
        assertTrue(fast.play());
        assertTrue(fast.volume() > slow.volume());
        assertTrue(fast.pitch() > slow.pitch());
        assertTrue(fast.nextCooldownTicks() < slow.nextCooldownTicks());
    }

    @Test
    void carriageMovementSoundSamplesFeetBlockForPartialRoadSurfaces() {
        assertEquals(new BlockPos(0, 64, 0),
                CarriageEntity.movementSoundSurfacePosForTest(
                        0.5D,
                        64.5D,
                        0.5D,
                        true,
                        new BlockPos(0, 64, 0)
                ));
    }

    @Test
    void carriageMovementSoundUsesBundledCarriageClipForSurfaceType() {
        assertEquals("sailboatmod:entity.carriage.move.stone",
                CarriageEntity.movementSoundEventPathForSurfaceForTest(Blocks.STONE_BRICKS.defaultBlockState()));
        assertEquals("sailboatmod:entity.carriage.move.grass",
                CarriageEntity.movementSoundEventPathForSurfaceForTest(Blocks.GRASS_BLOCK.defaultBlockState()));
        assertEquals("sailboatmod:entity.carriage.move.sand",
                CarriageEntity.movementSoundEventPathForSurfaceForTest(Blocks.SAND.defaultBlockState()));
        assertEquals("sailboatmod:entity.carriage.move.snow",
                CarriageEntity.movementSoundEventPathForSurfaceForTest(Blocks.SNOW_BLOCK.defaultBlockState()));
        assertEquals("sailboatmod:entity.carriage.move.wood",
                CarriageEntity.movementSoundEventPathForSurfaceForTest(Blocks.OAK_PLANKS.defaultBlockState()));
        assertEquals("sailboatmod:entity.carriage.move.ground",
                CarriageEntity.movementSoundEventPathForSurfaceForTest(Blocks.DIRT.defaultBlockState()));
    }

    @Test
    void carriageMovementSoundDoesNotUseAirOrFluidAsSurface() {
        assertFalse(CarriageEntity.hasMovementSoundSurfaceForTest(Blocks.AIR.defaultBlockState()));
        assertFalse(CarriageEntity.hasMovementSoundSurfaceForTest(Blocks.WATER.defaultBlockState()));
        assertEquals("", CarriageEntity.movementSoundEventPathForSurfaceForTest(Blocks.AIR.defaultBlockState()));
        assertEquals("", CarriageEntity.movementSoundEventPathForSurfaceForTest(Blocks.WATER.defaultBlockState()));
    }
}
