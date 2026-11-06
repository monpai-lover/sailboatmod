package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.compat.flightcontrol.FlightControlBridge.ControlOwnership;
import com.monpai.sailboatmod.transport.air.FixedWingGroundPolicy;
import com.monpai.sailboatmod.transport.air.airport.GroundAssignment;
import com.monpai.sailboatmod.transport.air.ground.AircraftGroundMission;
import com.monpai.sailboatmod.transport.air.ground.AircraftGroundMissionNbt;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Registrar-owned binding of two airports to one physical ground/flight mission. */
record RegisteredAircraftGroundMission(BlockPos sourceAirport, BlockPos targetAirport,
        GroundAssignment sourceAssignment, GroundAssignment targetAssignment,
        AircraftGroundMission.Plan plan, AircraftGroundMission.State state) {
    RegisteredAircraftGroundMission {
        sourceAirport=Objects.requireNonNull(sourceAirport).immutable();
        targetAirport=Objects.requireNonNull(targetAirport).immutable();
        Objects.requireNonNull(sourceAssignment);Objects.requireNonNull(targetAssignment);
        Objects.requireNonNull(plan);Objects.requireNonNull(state);
        if(sourceAirport.equals(targetAirport) || !sourceAssignment.runwayId().equals(plan.runwayId())
                || !targetAssignment.standId().equals(plan.standId()))throw new IllegalArgumentException("ground_binding_changed");
    }
    RegisteredAircraftGroundMission withState(AircraftGroundMission.State next) {
        return new RegisteredAircraftGroundMission(sourceAirport,targetAirport,sourceAssignment,targetAssignment,plan,next);
    }
    boolean matches(BlockPos source,long sourceRevision,BlockPos target,long targetRevision) {
        return sourceAirport.equals(source)&&targetAirport.equals(target)
                &&sourceAssignment.layoutRevision()==sourceRevision&&targetAssignment.layoutRevision()==targetRevision;
    }
    CompoundTag save() {
        CompoundTag tag=new CompoundTag();tag.putInt("Version",1);
        tag.putLong("SourceAirport",sourceAirport.asLong());tag.putLong("TargetAirport",targetAirport.asLong());
        tag.put("SourceAssignment",sourceAssignment.save());tag.put("TargetAssignment",targetAssignment.save());
        tag.put("Mission",AircraftGroundMissionNbt.save(plan,state));return tag;
    }
    static Optional<RegisteredAircraftGroundMission> loadOptional(CompoundTag tag) {
        try {
            if(tag==null||tag.getInt("Version")!=1||!tag.contains("SourceAirport",Tag.TAG_LONG)
                    ||!tag.contains("TargetAirport",Tag.TAG_LONG)||!tag.contains("SourceAssignment",Tag.TAG_COMPOUND)
                    ||!tag.contains("TargetAssignment",Tag.TAG_COMPOUND)||!tag.contains("Mission",Tag.TAG_COMPOUND))return Optional.empty();
            return AircraftGroundMissionNbt.loadOptional(tag.getCompound("Mission")).map(saved->new RegisteredAircraftGroundMission(
                    BlockPos.of(tag.getLong("SourceAirport")),BlockPos.of(tag.getLong("TargetAirport")),
                    GroundAssignment.load(tag.getCompound("SourceAssignment")),GroundAssignment.load(tag.getCompound("TargetAssignment")),saved.plan(),saved.state()));
        }catch(IllegalArgumentException malformed){return Optional.empty();}
    }
    boolean deliveryReady(BlockPos airport,boolean assigned,boolean nativeGround,boolean bindingsMatch,double speed,double angular) {
        return targetAirport.equals(airport)&&FixedWingGroundPolicy.standDeliveryReady(state.phase().name(),state.recovered(),
                assigned,nativeGround,bindingsMatch,speed,angular,state.stableTicks());
    }
    static AircraftGroundMission.Lease lease(ControlOwnership ownership,UUID body,UUID task,String fingerprint,boolean groundOwned) {
        if(ownership==null||!ownership.available())return AircraftGroundMission.Lease.UNKNOWN;
        if(ownership.foreignLease())return AircraftGroundMission.Lease.FOREIGN;
        if(ownership.provesFlight(body,task,fingerprint))return AircraftGroundMission.Lease.FC;
        if(ownership.provesReleased(body))return groundOwned?AircraftGroundMission.Lease.GROUND:AircraftGroundMission.Lease.NONE;
        return AircraftGroundMission.Lease.UNKNOWN;
    }
    static boolean exactCompletion(com.monpai.sailboatmod.compat.flightcontrol.FlightControlBridge.TerminalSnapshot snapshot,
            UUID body,UUID task,String fingerprint) {
        return snapshot != null && snapshot.completed() && task != null && task.equals(snapshot.taskId())
                && body != null && body.equals(snapshot.structureId()) && fingerprint != null && !fingerprint.isBlank()
                && snapshot.expected() != null && fingerprint.equals(snapshot.expected().profileFingerprint());
    }
    static boolean joinsRunwayEnds(com.monpai.sailboatmod.transport.air.airport.AirportFacility source,
            com.monpai.sailboatmod.transport.air.airport.AirportFacility target,net.minecraft.world.phys.Vec3 outboundEnd,net.minecraft.world.phys.Vec3 inboundStart,double corridorHalfWidth) {
        double tolerance = Math.min(.75, corridorHalfWidth);
        return Double.isFinite(corridorHalfWidth) && tolerance > 0 && source != null && target != null && outboundEnd != null && inboundStart != null
                && outboundEnd.subtract(net.minecraft.world.phys.Vec3.atCenterOf(source.start())).horizontalDistanceSqr() <= tolerance * tolerance
                && inboundStart.subtract(net.minecraft.world.phys.Vec3.atCenterOf(target.end())).horizontalDistanceSqr() <= tolerance * tolerance;
    }
    static boolean stoppingFits(double length,double speed,double deceleration,double diameter) {
        return com.monpai.sailboatmod.transport.air.FixedWingRunwayPolicy.stoppingFits(length,speed,deceleration,diameter);
    }
    enum StopMode { HOLD, YIELD, WAIT }
    record StopDecision(StopMode mode,AircraftGroundMission.Outputs outputs,boolean retainOwnership) { }
    static StopDecision stopDecision(boolean owned,boolean manual,UUID expectedBody,
            com.monpai.sailboatmod.transport.physical.NativeGroundObservation observation,ControlOwnership ownership,
            long tick,long sampledTick,AircraftGroundMission.Calibration calibration) {
        if (!owned || manual || ownership != null && ownership.available() && (ownership.anyActiveLease() || ownership.foreignLease()))
            return new StopDecision(StopMode.YIELD,null,false);
        if (tick < 0 || sampledTick != tick || expectedBody == null || observation == null || !observation.fullyGrounded()
                || !expectedBody.equals(observation.physicalId()) || ownership == null || !ownership.provesReleased(expectedBody)
                || calibration == null || !calibration.liveVerified() || calibration.neutralThrottleSignal() < 0 || calibration.neutralThrottleSignal() > 15)
            return new StopDecision(StopMode.WAIT,null,true);
        return new StopDecision(StopMode.HOLD,new AircraftGroundMission.Outputs(calibration.neutralThrottleSignal(),0,0,15,0),true);
    }
    static boolean rolloutReady(com.monpai.sailboatmod.transport.air.airport.AirportFacility runway,
            net.minecraft.world.phys.AABB hull,boolean nativeGround,double speed,double angularSpeed) {
        if (runway == null || !runway.supports(com.monpai.sailboatmod.transport.air.AircraftClass.FIXED_WING) || hull == null || !nativeGround
                || !Double.isFinite(speed) || speed < 0 || speed > .25 || !Double.isFinite(angularSpeed) || angularSpeed < 0 || angularSpeed > Math.toRadians(2)
                || !Double.isFinite(hull.minX) || !Double.isFinite(hull.maxX) || !Double.isFinite(hull.minY) || !Double.isFinite(hull.maxY)
                || !Double.isFinite(hull.minZ) || !Double.isFinite(hull.maxZ) || hull.getXsize() <= 0 || hull.getYsize() <= 0 || hull.getZsize() <= 0
                || hull.minY < runway.start().getY() - .5 || hull.maxY > runway.start().getY() + runway.clearanceHeight()) return false;
        return runway.containsHorizontal(hull.minX,hull.minZ,0) && runway.containsHorizontal(hull.minX,hull.maxZ,0)
                && runway.containsHorizontal(hull.maxX,hull.minZ,0) && runway.containsHorizontal(hull.maxX,hull.maxZ,0);
    }
    /** Calibration remains a draft until an operator confirms measurements for this exact hardware. */
    record Configuration(AircraftGroundMission.Calibration calibration,double taxiSpeed) {
        Configuration {
            Objects.requireNonNull(calibration);
            if(!positive(calibration.brakingDeceleration())||calibration.brakingDeceleration()>30
                    ||!positive(calibration.minimumTurnRadius())||calibration.minimumTurnRadius()>128
                    ||!positive(calibration.maximumSteeringDegrees())||calibration.maximumSteeringDegrees()>30
                    ||calibration.neutralThrottleSignal()<0||calibration.neutralThrottleSignal()>15
                    ||calibration.fullThrottleSignal()<0||calibration.fullThrottleSignal()>15
                    ||calibration.neutralThrottleSignal()==calibration.fullThrottleSignal()
                    ||!positive(taxiSpeed)||taxiSpeed>5)throw new IllegalArgumentException("invalid_setting");
        }
        static Configuration parse(String text) {
            String[] parts=text.strip().split("[ ,]+");if(parts.length!=6)throw new IllegalArgumentException("invalid_setting");
            return new Configuration(new AircraftGroundMission.Calibration(false,Double.parseDouble(parts[0]),Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]),Integer.parseInt(parts[3]),Integer.parseInt(parts[4])),Double.parseDouble(parts[5]));
        }
        Configuration confirmed() {
            var c=calibration;return new Configuration(new AircraftGroundMission.Calibration(true,c.brakingDeceleration(),c.minimumTurnRadius(),
                    c.maximumSteeringDegrees(),c.neutralThrottleSignal(),c.fullThrottleSignal()),taxiSpeed);
        }
        String text(){var c=calibration;return c.brakingDeceleration()+" "+c.minimumTurnRadius()+" "+c.maximumSteeringDegrees()+" "+c.neutralThrottleSignal()+" "+c.fullThrottleSignal()+" "+taxiSpeed;}
        private static boolean positive(double n){return Double.isFinite(n)&&n>0;}
    }
}
