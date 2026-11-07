package com.monpai.sailboatmod.compat.aeronautics;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.*;

class RailAircraftMotionStateTest {
    final Object controller = new Object(), world = new Object(), body = new Object();
    final UUID owner = UUID.randomUUID(), physical = UUID.randomUUID();
    final RailAircraftMotionState state = new RailAircraftMotionState();

    RailAircraftMotionState.Frame initial() {
        return new RailAircraftMotionState.Frame(new Vec3(3, 5, 7), new Quaterniond());
    }
    RailAircraftMotionState.Acquisition acquire() {
        return state.acquire(controller, world, body, owner, physical, Direction.WEST, initial(), false);
    }
    private RailAircraftMotionState.Acquisition acquireWithForeign(Object requester,Object actualBody,Direction forward,boolean live,String foreignReason) {
        try {
            var method=RailAircraftMotionState.class.getDeclaredMethod("acquire",Object.class,Object.class,Object.class,
                    UUID.class,UUID.class,Direction.class,RailAircraftMotionState.Frame.class,boolean.class,String.class);
            return (RailAircraftMotionState.Acquisition)method.invoke(state,requester,world,actualBody,owner,physical,forward,initial(),live,foreignReason);
        }catch(NoSuchMethodException missing){return fail("Body acquisition must evaluate foreign native control before taking a new lease",missing);}
        catch(InvocationTargetException failure){return fail("Foreign control rejection must preserve the existing safe hold",failure.getCause());}
        catch(ReflectiveOperationException failure){return fail("Foreign-control acquisition API failed",failure);}
    }

    @Test void aWorkingForeignControllerCannotGrantANewRailLease() {
        var rejected=acquireWithForeign(controller,body,Direction.WEST,false,"flight_control_conflict");
        assertFalse(rejected.accepted());assertEquals("flight_control_conflict",rejected.reason());
        assertNull(rejected.token());assertFalse(state.controls(body));assertNull(state.existing(physical));
        var first=acquire();assertTrue(first.accepted());assertEquals(1,first.token().generation());
    }

    @Test void unknownForeignControlReadbackCannotGrantANewLease() {
        for(String unknown:new String[]{"flight_control_state_unavailable",null}) {
            var rejected=acquireWithForeign(controller,body,Direction.WEST,false,unknown);
            assertFalse(rejected.accepted());assertEquals("flight_control_state_unavailable",rejected.reason());
            assertFalse(state.controls(body));assertNull(state.existing(physical));
        }
    }

    @Test void aLaterForeignConflictKeepsTheCurrentOwnersTokenAndAppliedHold() {
        var original=acquire();var token=original.token();
        state.move(controller,world,body,token,new Vec3(5,5,7),new Vec3(-1,0,0),0,100);
        Vec3 applied=state.advance(state.session(token),.025).anchor();
        for(String reason:new String[]{"flight_control_conflict","flight_control_state_unavailable",null}) {
            var same=acquireWithForeign(controller,body,Direction.WEST,true,reason);
            assertTrue(same.accepted());assertEquals(token,same.token());assertTrue(state.hold(controller,world,body,token).success());
            assertEquals(applied,state.advance(state.session(token),.05).anchor());assertTrue(state.controls(body));
        }
    }

    @Test void retainingTheExistingHoldStillRejectsAChangedNoseBinding() {
        var token=acquire().token();var rejected=acquireWithForeign(controller,body,Direction.EAST,true,"flight_control_conflict");
        assertFalse(rejected.accepted());assertEquals("air_rail_forward_changed",rejected.reason());
        assertTrue(state.owns(controller,world,body,token));assertTrue(state.hold(controller,world,body,token).success());
    }

    @Test void foreignControlBlocksDetachedRebindWithoutRemovingTheOldHold() {
        var old=acquire().token();state.detached(state.session(old));
        var rejected=acquireWithForeign(controller,body,Direction.WEST,true,"flight_control_conflict");
        assertFalse(rejected.accepted());assertEquals("flight_control_conflict",rejected.reason());
        assertTrue(state.owns(controller,world,body,old));assertTrue(state.controls(body));
        assertTrue(state.hold(controller,world,body,old).success());assertEquals(initial().anchor(),state.advance(state.session(old),.05).anchor());
        var accepted=acquireWithForeign(controller,body,Direction.WEST,true,"");
        assertTrue(accepted.accepted());assertEquals(old.generation()+1,accepted.token().generation());assertFalse(state.owns(controller,world,body,old));
    }

    @Test void unknownReadbackBlocksReplacementControllerAndBodyWithoutReleasingThePriorBody() {
        var old=acquire().token();Object replacement=new Object(),replacementBody=new Object();
        var rejected=acquireWithForeign(replacement,replacementBody,Direction.WEST,false,"flight_control_state_unavailable");
        assertFalse(rejected.accepted());assertEquals("flight_control_state_unavailable",rejected.reason());
        assertTrue(state.owns(controller,world,body,old));assertTrue(state.controls(body));assertFalse(state.controls(replacementBody));
        var accepted=acquireWithForeign(replacement,replacementBody,Direction.WEST,false,"");
        assertTrue(accepted.accepted());assertEquals(old.generation()+1,accepted.token().generation());
        assertFalse(state.controls(body));assertTrue(state.controls(replacementBody));assertTrue(state.owns(replacement,world,replacementBody,accepted.token()));
    }

    @Test void onePhysicalBodyHasOneOwnerAndARepeatDoesNotReplaceItsToken() {
        var first = acquire();
        assertTrue(first.accepted());
        assertEquals(first.token(), acquire().token());
        assertFalse(state.acquire(new Object(), world, body, UUID.randomUUID(), physical,
                Direction.WEST, initial(), true).accepted());
        assertTrue(state.owns(controller, world, body, first.token()));
    }

    @Test void unloadedControllerCanRebindButOldTokenCannotMoveOrReleaseTheNewSession() {
        var old = acquire().token();
        Object replacement = new Object();
        assertFalse(state.acquire(replacement, world, body, owner, physical,
                Direction.WEST, initial(), true).accepted());
        var next = state.acquire(replacement, world, body, owner, physical,
                Direction.WEST, initial(), false).token();
        assertNotEquals(old, next);
        assertFalse(state.owns(controller, world, body, old));
        assertFalse(state.release(controller, old));
        assertTrue(state.owns(replacement, world, body, next));
    }

    @Test void wrongDimensionAndDifferentLiveBodyObjectAreRejected() {
        var token = acquire().token();
        assertFalse(state.owns(controller, new Object(), body, token));
        assertFalse(state.owns(controller, world, new Object(), token));
        assertFalse(state.acquire(controller, new Object(), body, owner, physical,
                Direction.WEST, initial(), true).accepted());
    }

    @Test void targetInterpolatesOverOneGameTickAndMissingHeartbeatKeepsTheFinalPose() {
        var token = acquire().token();
        assertTrue(state.move(controller, world, body, token, new Vec3(5, 5, 7),
                new Vec3(-1, 0, 0), 0, 100).success());
        var session = state.session(token);
        assertEquals(4, state.advance(session, .025).anchor().x, 1e-10);
        assertEquals(5, state.advance(session, .025).anchor().x, 1e-10);
        for (int i = 0; i < 200; i++) assertEquals(5, state.advance(session, .025).anchor().x, 1e-10);
        assertTrue(state.owns(controller, world, body, token));
    }

    @Test void duplicateTargetDoesNotRestartInterpolationAndConflictingSameTickTargetIsRejected() {
        var token = acquire().token();
        Vec3 end = new Vec3(5, 5, 7), tangent = new Vec3(-1, 0, 0);
        assertTrue(state.move(controller, world, body, token, end, tangent, 0, 100).success());
        state.advance(state.session(token), .025);
        assertTrue(state.move(controller, world, body, token, end, tangent, 0, 100).success());
        assertFalse(state.move(controller, world, body, token, end.add(1, 0, 0), tangent, 0, 100).success());
        assertEquals(5, state.advance(state.session(token), .025).anchor().x, 1e-10);
        assertFalse(state.move(controller, world, body, token, end, tangent, 0, 99).success());
    }

    @Test void explicitHoldStopsAtTheLastAppliedPoseAndReleaseAffectsOnlyItsBody() {
        var token = acquire().token();
        state.move(controller, world, body, token, new Vec3(5, 5, 7), new Vec3(-1, 0, 0), 0, 100);
        state.advance(state.session(token), .025);
        assertTrue(state.hold(controller, world, body, token).success());
        assertEquals(4, state.advance(state.session(token), .05).anchor().x, 1e-10);
        assertTrue(state.controls(body));
        assertFalse(state.controls(new Object()));
        assertTrue(state.release(controller, token));
        assertFalse(state.controls(body));
    }

    @Test void invalidNumbersVerticalForwardAndLargeJumpCannotChangeTheTarget() {
        var token = acquire().token();
        assertFalse(state.move(controller, world, body, token, new Vec3(Double.NaN, 0, 0),
                new Vec3(-1, 0, 0), 0, 100).success());
        assertFalse(state.move(controller, world, body, token, initial().anchor(), Vec3.ZERO, 0, 100).success());
        assertFalse(state.move(controller, world, body, token, initial().anchor(), new Vec3(0, 1, 0), 0, 100).success());
        assertFalse(state.move(controller, world, body, token, new Vec3(1000, 0, 0), new Vec3(-1, 0, 0), 0, 100).success());
        assertThrows(IllegalArgumentException.class, () -> state.advance(state.session(token), Double.NaN));
        assertEquals(initial().anchor(), state.advance(state.session(token), .05).anchor());
    }

    @Test void eachHorizontalRegistrationMapsItsOwnForwardToTheRequestedTangent() {
        for (Direction facing : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            Quaterniond q = RailAircraftMotionState.orientation(facing, new Vec3(1, .2, 1), 0);
            Vector3d actual = q.transform(new Vector3d(facing.getStepX(), 0, facing.getStepZ())).normalize();
            Vector3d expected = new Vector3d(1, .2, 1).normalize();
            assertEquals(0, actual.distance(expected), 1e-9);
        }
    }

    @Test void bankTurnsTheAircraftUpVectorIntoTheInsideOfTheTurn() {
        Vector3d upXtoZ = RailAircraftMotionState.orientation(Direction.EAST, new Vec3(1, 0, 0), .3)
                .transform(new Vector3d(0, 1, 0));
        assertTrue(upXtoZ.z > 0);
        Vector3d upZtoX = RailAircraftMotionState.orientation(Direction.SOUTH, new Vec3(0, 0, 1), -.3)
                .transform(new Vector3d(0, 1, 0));
        assertTrue(upZtoX.x > 0);
    }

    @Test void anchorConversionPreservesOffsetPivotScaleAndWholeBodyOrientation() {
        Vec3 anchor = new Vec3(100, 12, -50), pivot = new Vec3(90, 9, -48), scale = new Vec3(1, 2, 1);
        Quaterniond q = new Quaterniond().rotateY(.8).rotateZ(.2);
        Vec3 desired = new Vec3(800, 200, 1000);
        Vector3d position = RailAircraftMotionState.posePosition(anchor, pivot, scale, q, desired);
        Vector3d actual = new Vector3d(anchor.x - pivot.x, anchor.y - pivot.y, anchor.z - pivot.z)
                .mul(scale.x, scale.y, scale.z);
        q.transform(actual).add(position);
        assertEquals(0, actual.distance(new Vector3d(desired.x, desired.y, desired.z)), 1e-9);
    }

    @Test void everyLevelHeadingRemainsUprightIncludingTheOppositeHeading() {
        for (Direction local : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST})
            for (Direction desired : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
                var q = RailAircraftMotionState.orientation(local,
                        new Vec3(desired.getStepX(), 0, desired.getStepZ()), 0);
                assertEquals(0, q.transform(new Vector3d(0, 1, 0)).distance(new Vector3d(0, 1, 0)), 1e-9);
            }
    }

    @Test void headingInterpolationCrossesTheYawBoundaryByTheShortestUprightArc() {
        double start = Math.toRadians(179), end = Math.toRadians(-179);
        var frame = new RailAircraftMotionState.Frame(initial().anchor(),
                RailAircraftMotionState.orientation(Direction.EAST, new Vec3(Math.cos(start), 0, Math.sin(start)), 0));
        var token = state.acquire(controller, world, body, owner, physical, Direction.EAST, frame, false).token();
        assertTrue(state.move(controller, world, body, token, frame.anchor(),
                new Vec3(Math.cos(end), 0, Math.sin(end)), 0, 100).success());
        Quaterniond midpoint = state.advance(state.session(token), .025).orientation();
        assertEquals(0, midpoint.transform(new Vector3d(1, 0, 0)).distance(new Vector3d(-1, 0, 0)), 1e-9);
        assertEquals(0, midpoint.transform(new Vector3d(0, 1, 0)).distance(new Vector3d(0, 1, 0)), 1e-9);
    }

    @Test void poseAcknowledgmentIncludesBankAndCannotBeSatisfiedByAPendingTarget() {
        Vec3 anchor = initial().anchor(), forward = new Vec3(-1, 0, 0);
        assertTrue(RailAircraftMotionState.matches(initial(), Direction.WEST, anchor, forward, 0, .05, .02));
        assertFalse(RailAircraftMotionState.matches(initial(), Direction.WEST, anchor.add(1, 0, 0), forward, 0, .05, .02));
        assertFalse(RailAircraftMotionState.matches(initial(), Direction.WEST, anchor, forward, .2, .05, .02));
        var banked = new RailAircraftMotionState.Frame(anchor,
                RailAircraftMotionState.orientation(Direction.WEST, forward, .2));
        assertTrue(RailAircraftMotionState.matches(banked, Direction.WEST, anchor, forward, .2, .05, .02));
        assertFalse(RailAircraftMotionState.matches(banked, Direction.WEST, anchor, forward, .2, Double.NaN, .02));
    }

    @Test void returningSameControllerObjectAfterUnloadAlsoInvalidatesOldToken() {
        var token = acquire().token();
        state.detached(state.session(token));
        assertTrue(state.controls(body));
        var next = acquire().token();
        assertNotEquals(token, next);
        assertFalse(state.owns(controller, world, body, token));
    }

    @Test void invalidNativeScaleOrQuaternionCannotReachTheMotionSink() {
        assertThrows(IllegalArgumentException.class, () -> RailAircraftMotionState.posePosition(
                new Vec3(10, 10, 10), Vec3.ZERO, new Vec3(Double.MAX_VALUE, 1, 1),
                new Quaterniond(), Vec3.ZERO));
        assertThrows(IllegalArgumentException.class, () -> RailAircraftMotionState.posePosition(
                Vec3.ZERO, Vec3.ZERO, new Vec3(1, 1, 1), new Quaterniond(0, 0, 0, Double.NaN), Vec3.ZERO));
    }

    @Test void newUnsupportedConnectionStopsTheAcceptedSegmentAndCannotResumeThroughHold() {
        var token = acquire().token();
        state.move(controller, world, body, token, new Vec3(5, 5, 7), new Vec3(-1, 0, 0), 0, 100);
        state.advance(state.session(token), .025);
        state.failHolding(state.session(token), "air_rail_multi_body_unsupported");
        assertEquals("air_rail_multi_body_unsupported", state.status(controller, world, token).reason());
        assertEquals(4, state.advance(state.session(token), .025).anchor().x, 1e-10);
        state.hold(controller, world, body, token);
        var rejected = state.move(controller, world, body, token, new Vec3(6, 5, 7), new Vec3(-1, 0, 0), 0, 101);
        assertFalse(rejected.success());
        assertEquals("air_rail_multi_body_unsupported", rejected.reason());
        assertTrue(state.controls(body));
        assertEquals("air_rail_stale_lease", state.status(new Object(), world, token).reason());
    }

    @Test void nativePipelineDisposalInvalidatesOnlyItsOwnLeases() {
        var token = acquire().token();
        Object pipeline = new Object();
        state.session(token).nativePipeline = pipeline;
        Object otherController = new Object(), otherBody = new Object();
        var other = state.acquire(otherController, world, otherBody, owner, UUID.randomUUID(),
                Direction.WEST, initial(), false).token();
        state.session(other).nativePipeline = new Object();
        state.pipelineDisposed(pipeline);
        assertFalse(state.controls(body));
        assertFalse(state.owns(controller, world, body, token));
        assertTrue(state.owns(otherController, world, otherBody, other));
    }
}
