package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.compat.aeronautics.RailAircraftMotionBridge;
import com.monpai.sailboatmod.transport.LogisticsCarrier;
import com.monpai.sailboatmod.transport.air.RailFlightPlanBuilder;
import com.monpai.sailboatmod.transport.air.airport.*;
import com.monpai.sailboatmod.transport.air.rail.*;
import com.monpai.sailboatmod.transport.physical.PhysicalVehicleView;
import com.monpai.sailboatmod.transport.physical.NativeControlResult;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.Function;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/** Owns the visual aircraft procedure; cargo remains in the registered carrier's real inventory. */
final class RegisteredAircraftRailController {
    record AdmissionPlan(RailAircraftPath path,GroundAssignment source,GroundAssignment destination) { }
    record Context(ServerLevel server, BlockEntity controller, LogisticsCarrier carrier,
                   PhysicalVehicleView view, Direction localForward) {
        UUID owner() { return carrier.getTransportUuid(); }
        double yaw() { return Math.toDegrees(Math.atan2(-view.worldForward().x,view.worldForward().z)); }
        double angularSpeed() { return RailAircraftMotionBridge.angularSpeed(controller).orElse(Double.NaN); }
    }
    private RailAircraftMission mission;
    private RailAircraftMotionBridge.Lease lease;
    private Direction missionForward,leaseForward;
    private RailAircraftMission.Step pending;
    private UUID preparation=UUID.randomUUID();
    private RailAirportSelection.Selection selected;
    private RailAircraftPath selectedPath;
    private final Set<GroundAssignment> blockedSources=new HashSet<>(),blockedTargets=new HashSet<>();
    private long retryAt=-1,renewedAt=-1,lastTick=-1;
    private boolean invalid,cancelled,manualReleased,rebindRequired,reloadAlignmentAvailable;
    private CompoundTag invalidState;
    private String reason="";

    boolean prepare(Context c,AirportBlockEntity source,AirportBlockEntity target,List<Vec3> cruise,double speed,
                    GroundAssignment exactSource,GroundAssignment exactTarget,Function<AdmissionPlan,NativeControlResult> admission) {
        if(invalid||source==null||target==null||source==target)return waitFor("rail_recovery_review");
        if(mission!=null&&!mission.completed()&&!cancelled)return waitFor("rail_mission_already_active");
        if(!RailAircraftMotionBridge.available(c.controller()))return waitFor("rail_motion_unavailable");
        manualReleased=false;
        if(!acquire(c))return false;
        long now=c.server().getGameTime();
        if(retryAt>=0&&now>=retryAt){blockedSources.clear();blockedTargets.clear();selected=null;selectedPath=null;retryAt=-1;}
        if(retryAt>now)return waitFor("airport_unavailable");
        for(int rejected=0;rejected<4;rejected++) {
            if(selected==null) {
                var aircraft=new RailAirportSelection.Aircraft(c.view().physicalId(),c.view().worldBounds(),c.view().worldPosition(),c.yaw());
                Map<RailAirportSelection.Selection,RailAircraftPath> paths=new HashMap<>();
                var search=RailAirportSelection.select(aircraft,source.groundLayout(),source.facilities(),exactSource,
                        target.groundLayout(),target.facilities(),exactTarget,s->{
                            if(blockedSources.contains(s.source().assignment())||blockedTargets.contains(s.destination().assignment()))return false;
                            try {
                                paths.put(s,RailFlightPlanBuilder.build(s.source().runway(),s.destination().runway(),s.source().outbound(),s.destination().inbound(),
                                        s.destination().stand(),s.anchorAboveSurface(),s.sweepDiameter(),cruise,speed,
                                        s.source().stand(),c.view().worldPosition(),c.view().worldForward()));return true;
                            } catch(IllegalArgumentException badPath){return false;}
                        });
                if(search.selection().isEmpty()){retryAt=now+20;return waitFor(search.reason());}
                selected=search.selection().orElseThrow();selectedPath=paths.get(selected);
                if(selectedPath==null)return waitFor("rail_path_invalid");
            }
            var admitted=admission==null?NativeControlResult.failure("fuel_unknown"):
                    admission.apply(new AdmissionPlan(selectedPath,selected.source().assignment(),selected.destination().assignment()));
            if(admitted==null||!admitted.success())return waitFor(admitted==null?"fuel_unknown":admitted.reason());
            var from=source.prepareGroundCandidate(preparation,c.owner(),selected.source().assignment());
            if(from.status()==AirportGroundPreparation.Status.WAITING)return waitFor("airport_reserved".equals(from.reason())?from.reason():"waiting_airport_terrain");
            if(from.status()==AirportGroundPreparation.Status.BLOCKED){blockedSources.add(selected.source().assignment());selected=null;selectedPath=null;continue;}
            var to=target.prepareGroundCandidate(preparation,c.owner(),selected.destination().assignment());
            if(to.status()==AirportGroundPreparation.Status.WAITING)return waitFor("airport_reserved".equals(to.reason())?to.reason():"waiting_airport_terrain");
            if(to.status()==AirportGroundPreparation.Status.BLOCKED){blockedTargets.add(selected.destination().assignment());selected=null;selectedPath=null;continue;}
            if(c.view().worldPosition().distanceTo(selectedPath.sample(0).position())>.05)return waitFor("rail_start_alignment_required");
            if(!source.reserveGroundAssignment(preparation,selected.source().assignment(),c.owner(),200)
                    ||!target.reserveGroundAssignment(preparation,selected.destination().assignment(),c.owner(),200)){
                source.releaseReservation(c.owner());target.releaseReservation(c.owner());return waitFor("airport_reserved");
            }
            mission=RailAircraftMission.start(preparation,c.view().physicalId(),c.carrier().dimensionId(),source.getBlockPos(),selected.source().assignment(),
                    target.getBlockPos(),selected.destination().assignment(),selectedPath);
            missionForward=c.localForward();cancelled=false;pending=null;renewedAt=now;lastTick=-1;rebindRequired=false;reloadAlignmentAvailable=false;reason="";return true;
        }
        return waitFor("airport_unavailable");
    }

    /** Actual readback of the previous command always precedes the next distance proposal. */
    void tick(Context c,boolean active,boolean paused,Predicate<RailAircraftPath.Sample> pathClear,
              Function<RailAircraftPath.Sample,NativeControlResult> admission) {
        if(manualReleased){reason="manual_control";return;}
        if(invalid){reason="rail_recovery_review";hold(c);return;}
        if(mission==null)return;
        if(!mission.bodyId().equals(c.view().physicalId())||!mission.dimension().equals(c.carrier().dimensionId())){
            reason="rail_body_changed";hold(c);return;
        }
        if(!acquire(c))return;
        long now=c.server().getGameTime();
        if(now==lastTick)return;lastTick=now;
        var driver=RailAircraftMotionBridge.status(c.controller(),lease);
        if(!driver.success()){reason=driver.reason();hold(c);return;}
        if(!active||cancelled||mission.completed()){hold(c);reason="";return;}
        AirportBlockEntity source=airport(c,mission.source()),target=airport(c,mission.target());
        if(source==null||target==null){reason="waiting_airport_terrain";hold(c);return;}
        if(source.groundLayoutRevision()!=mission.sourceAssignment().layoutRevision()||target.groundLayoutRevision()!=mission.targetAssignment().layoutRevision()){
            reason="ground_binding_changed";hold(c);return;
        }
        if(now<renewedAt||now-renewedAt>=20||!source.holdsGroundAssignment(mission.sourceAssignment(),c.owner())||!target.holdsGroundAssignment(mission.targetAssignment(),c.owner())) {
            var from=source.prepareGroundCandidate(mission.id(),c.owner(),mission.sourceAssignment());
            var to=target.prepareGroundCandidate(mission.id(),c.owner(),mission.targetAssignment());
            if(from.status()!=AirportGroundPreparation.Status.READY||to.status()!=AirportGroundPreparation.Status.READY
                    ||!source.reserveGroundAssignment(mission.id(),mission.sourceAssignment(),c.owner(),200)
                    ||!target.reserveGroundAssignment(mission.id(),mission.targetAssignment(),c.owner(),200)){
                reason="waiting_airport_terrain";hold(c);return;
            }
            renewedAt=now;
        }
        if(rebindRequired) {
            var current=mission.path().sample(mission.distance());
            if(pending==null) {
                if(!RailAircraftMotionBridge.matchesTarget(c.controller(),lease,current.position(),current.tangent(),current.bankRadians(),.05,.03)){
                    // Sable's first physics tick precedes controller BE tick after a process restart.
                    // Recover only a small downward startup displacement, with actual pose acknowledgment and no distance credit.
                    if(reloadAlignmentAvailable&&RailReloadAlignment.permits(current.position(),c.view().worldPosition())
                            &&RailAircraftMotionBridge.matchesTarget(c.controller(),lease,c.view().worldPosition(),current.tangent(),current.bankRadians(),.001,.03)) {
                        if(!pathClear.test(current)){blocked(c,"rail_path_blocked");return;}
                        reloadAlignmentAvailable=false;
                        var aligned=RailAircraftMotionBridge.move(c.controller(),lease,current.position(),current.tangent(),current.bankRadians());
                        if(!aligned.success()){reason=aligned.reason();hold(c);return;}
                        reason="rail_motion_rebinding";return;
                    }
                    reason="rail_position_changed";hold(c);return;
                }
                rebindRequired=false;reloadAlignmentAvailable=false;
            } else if(!matches(c,pending.sample())) {
                if(paused){reason="rail_motion_waiting";hold(c);return;}
                Vec3 delta=pending.sample().position().subtract(current.position()),actual=c.view().worldPosition();
                double fraction=delta.lengthSqr()<1e-12?0:actual.subtract(current.position()).dot(delta)/delta.lengthSqr();
                Vec3 projected=current.position().add(delta.scale(Math.max(0,Math.min(1,fraction))));
                Vec3 nose=current.tangent().lerp(pending.sample().tangent(),Math.max(0,Math.min(1,fraction))).normalize();
                double bank=current.bankRadians()+(pending.sample().bankRadians()-current.bankRadians())*Math.max(0,Math.min(1,fraction));
                if(fraction<-.01||fraction>1.01||actual.distanceTo(projected)>.05
                        ||!RailAircraftMotionBridge.matchesTarget(c.controller(),lease,actual,nose,bank,.05,.06)){
                    reason="rail_position_changed";hold(c);return;
                }
                // Requeue only the exact saved, uncredited step after validating the saved segment and resources.
                var admitted=admission==null?NativeControlResult.failure("fuel_unknown"):admission.apply(pending.sample());
                if(admitted==null||!admitted.success()){blocked(c,admitted==null?"fuel_unknown":admitted.reason());return;}
                if(!pathClear.test(pending.sample())){blocked(c,"rail_path_blocked");return;}
                var moved=RailAircraftMotionBridge.move(c.controller(),lease,pending.sample().position(),pending.sample().tangent(),pending.sample().bankRadians());
                if(!moved.success()){reason=moved.reason();hold(c);return;}
                rebindRequired=false;reason="rail_motion_rebinding";return;
            } else rebindRequired=false;
        }
        if(pending!=null) {
            var p=pending.sample();
            boolean matched=matches(c,p);
            if(!matched){reason="rail_motion_waiting";return;}
            if(!mission.commitStep(pending,true)){reason="rail_progress_changed";hold(c);return;}
            pending=null;
        }
        mission.setPaused(paused);
        if(paused){hold(c);reason="";return;}
        mission.clearWait();
        if(mission.distance()==mission.path().length()) {
            hold(c);
            boolean parked=target.railStandContains(mission.targetAssignment(),c.carrier(),mission.bodyId(),c.view(),c.yaw(),c.angularSpeed(),now)
                    &&RailAircraftMotionBridge.matchesTarget(c.controller(),lease,mission.path().sample(mission.path().length()).position(),
                    mission.path().sample(mission.path().length()).tangent(),0,.05,.02);
            mission.observeStand(now,parked);reason=parked?"":"rail_stand_alignment";return;
        }
        var step=mission.proposeStep(true);
        var admitted=admission==null?NativeControlResult.failure("fuel_unknown"):admission.apply(step.sample());
        if(admitted==null||!admitted.success()){blocked(c,admitted==null?"fuel_unknown":admitted.reason());return;}
        if(!pathClear.test(step.sample())){blocked(c,"rail_path_blocked");return;}
        var moved=RailAircraftMotionBridge.move(c.controller(),lease,step.sample().position(),step.sample().tangent(),step.sample().bankRadians());
        if(!moved.success()){blocked(c,moved.reason());return;}
        pending=step;reason="";
    }

    boolean ready(Context c,AirportBlockEntity airport) {
        return mission!=null&&!cancelled&&!invalid&&mission.readyToComplete()&&airport.getBlockPos().equals(mission.target())
                &&RailAircraftMotionBridge.owns(c.controller(),lease)
                &&airport.railAssignedStandArrival(mission.targetAssignment(),c.carrier(),mission.bodyId(),c.view(),c.yaw(),c.angularSpeed(),mission.actualStandTicks(),c.server().getGameTime());
    }
    boolean complete() { return mission!=null&&mission.complete(); }
    boolean owns(Context c) { return RailAircraftMotionBridge.owns(c.controller(),lease); }
    Optional<net.minecraft.world.phys.AABB> sweptBounds(Context c,RailAircraftPath.Sample next) {
        return RailAircraftMotionBridge.sweptBounds(c.controller(),lease,c.view().worldBounds(),next.position(),next.tangent(),next.bankRadians());
    }
    void park(Context c){if(!manualReleased&&acquire(c))hold(c);}
    boolean hasMission(){return mission!=null;}
    boolean cancelled(){return cancelled;}
    /** A queued new departure may outlive an old cancellation; full preparation still runs before motion. */
    boolean canResume(boolean preflightPending) {
        if(invalid||mission==null&&!preflightPending)return false;
        return !cancelled||preflightPending&&(mission==null||mission.completed());
    }
    String reason(){return reason;}
    void cancel(Context c){cancelled=true;pending=null;reloadAlignmentAvailable=false;if(mission!=null)mission.setPaused(true);hold(c);}
    void manualRelease(Context c){manualReleased=true;reloadAlignmentAvailable=false;if(lease!=null)RailAircraftMotionBridge.release(c.controller(),lease);lease=null;}
    void allowResume(){manualReleased=false;}
    void permanentRelease(Context c){manualRelease(c);mission=null;pending=null;selected=null;selectedPath=null;missionForward=leaseForward=null;}
    void resetPreparation(){selected=null;selectedPath=null;blockedSources.clear();blockedTargets.clear();retryAt=-1;preparation=UUID.randomUUID();}
    private boolean acquire(Context c) {
        if((mission!=null&&!mission.completed()&&!cancelled&&missionForward!=c.localForward())
                ||lease!=null&&leaseForward!=c.localForward()){
            hold(c);return waitFor("air_rail_forward_changed");
        }
        if(lease!=null&&RailAircraftMotionBridge.owns(c.controller(),lease))return true;
        var result=RailAircraftMotionBridge.acquire(c.controller(),c.owner(),c.view().physicalId(),c.localForward());
        if(!result.accepted())return waitFor(result.reason());lease=result.lease();leaseForward=c.localForward();if(mission!=null)rebindRequired=true;return true;
    }
    private boolean matches(Context c,RailAircraftPath.Sample p){return RailAircraftMotionBridge.matchesTarget(c.controller(),lease,p.position(),p.tangent(),p.bankRadians(),.05,.02);}
    private void hold(Context c){if(lease!=null)RailAircraftMotionBridge.hold(c.controller(),lease);if(pending!=null)rebindRequired=true;}
    private void blocked(Context c,String why){reason=why;mission.waitFor(why);mission.observeStand(c.server().getGameTime(),false);hold(c);}
    private boolean waitFor(String why){reason=why==null||why.isBlank()?"airport_unavailable":why;return false;}
    private static AirportBlockEntity airport(Context c,net.minecraft.core.BlockPos pos){return c.server().hasChunkAt(pos)&&c.server().getBlockEntity(pos) instanceof AirportBlockEntity a?a:null;}

    static RegisteredAircraftRailController load(CompoundTag tag) {
        var c=new RegisteredAircraftRailController();
        try {
            if(tag.contains("Invalid")){c.invalid=true;c.invalidState=tag.copy();c.reason="rail_recovery_review";return c;}
            if(tag.contains("Mission")) {
                if(!tag.contains("Mission",Tag.TAG_COMPOUND))throw new IllegalArgumentException("rail_mission_type");
                c.mission=RailAircraftMission.load(tag.getCompound("Mission"));if(!c.mission.completed())c.preparation=c.mission.id();c.rebindRequired=true;
                if(!tag.contains("Forward",Tag.TAG_STRING))throw new IllegalArgumentException("rail_forward_missing");
                c.missionForward=Direction.byName(tag.getString("Forward"));
                if(c.missionForward==null||!c.missionForward.getAxis().isHorizontal())throw new IllegalArgumentException("rail_forward_invalid");
                if(tag.contains("PendingDistance")) {
                    if(!tag.contains("PendingDistance",Tag.TAG_DOUBLE))throw new IllegalArgumentException("rail_pending_type");
                    double target=tag.getDouble("PendingDistance");var expected=c.mission.proposeStep(true);
                    if(!Double.isFinite(target)||target<=c.mission.distance()||Double.compare(target,expected.targetDistance())!=0)
                        throw new IllegalArgumentException("rail_pending_progress");
                    c.pending=expected;
                }
                c.reloadAlignmentAvailable=c.pending==null&&!c.mission.completed()&&!tag.getBoolean("Cancelled");
            } else if(tag.contains("PendingDistance"))throw new IllegalArgumentException("rail_pending_without_mission");
            c.cancelled=tag.getBoolean("Cancelled");
        }catch(IllegalArgumentException malformed){c.invalid=true;c.invalidState=tag.copy();c.reason="rail_recovery_review";}
        return c;
    }
    RailAircraftMission mission() { return mission; }
    UUID preparationId() { return preparation; }
    Double pendingDistance() { return pending==null?null:pending.targetDistance(); }
    boolean invalid() { return invalid; }
    CompoundTag save() {
        if(invalid){var t=invalidState==null?new CompoundTag():invalidState.copy();t.putBoolean("Invalid",true);return t;}
        var t=new CompoundTag();if(mission!=null){t.put("Mission",mission.save());t.putString("Forward",missionForward.getName());}if(pending!=null)t.putDouble("PendingDistance",pending.targetDistance());t.putBoolean("Cancelled",cancelled);return t;
    }
}
