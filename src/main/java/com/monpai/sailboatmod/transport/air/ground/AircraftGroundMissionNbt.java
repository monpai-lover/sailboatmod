package com.monpai.sailboatmod.transport.air.ground;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import static com.monpai.sailboatmod.transport.air.ground.AircraftGroundMission.*;

/** Bounded persistence for pure mission data; loading never resumes automation or settles cargo by itself. */
public final class AircraftGroundMissionNbt {
    private static final int VERSION=1;
    public record Saved(Plan plan,State state) { }
    private AircraftGroundMissionNbt() { }

    public static CompoundTag save(Plan p,State s) {
        if (!AircraftGroundMission.validPlan(p) || !validState(p,s)) throw new IllegalArgumentException("Invalid ground mission");
        CompoundTag tag=new CompoundTag(), plan=new CompoundTag(), state=new CompoundTag(), calibration=new CompoundTag();
        tag.putInt("Version",VERSION);
        binding(plan,p.missionId(),p.physicalId(),p.dimensionId(),p.layoutRevision(),p.runwayId(),p.standId(),p.reservationId());
        plan.put("Outbound",points(p.outbound()));plan.putInt("HoldPointIndex",p.holdPointIndex());plan.put("Inbound",points(p.inbound()));
        plan.putDouble("RunwayYaw",p.runwayYawDegrees());plan.putDouble("StandYaw",p.standYawDegrees());
        plan.putDouble("SpeedLimit",p.speedLimit());plan.putDouble("CorridorHalfWidth",p.corridorHalfWidth());
        Calibration c=p.calibration();calibration.putBoolean("LiveVerified",c.liveVerified());
        calibration.putDouble("BrakingDeceleration",c.brakingDeceleration());calibration.putDouble("MinimumTurnRadius",c.minimumTurnRadius());
        calibration.putDouble("MaximumSteeringDegrees",c.maximumSteeringDegrees());
        calibration.putInt("NeutralThrottleSignal",c.neutralThrottleSignal());calibration.putInt("FullThrottleSignal",c.fullThrottleSignal());
        plan.put("Calibration",calibration);
        binding(state,s.missionId(),s.physicalId(),s.dimensionId(),s.layoutRevision(),s.runwayId(),s.standId(),s.reservationId());
        state.putString("Phase",s.phase().name());state.putInt("PointIndex",s.pointIndex());state.putInt("StableTicks",s.stableTicks());
        state.putLong("LastObservationTick",s.lastObservationTick());state.putBoolean("Recovered",s.recovered());state.putInt("LastThrottleSignal",s.lastThrottleSignal());
        tag.put("Plan",plan);tag.put("State",state);return tag;
    }

    public static Optional<Saved> loadOptional(CompoundTag tag) {
        try {
            if (tag==null || !tag.contains("Version",Tag.TAG_INT) || tag.getInt("Version")!=VERSION
                    || !tag.contains("Plan",Tag.TAG_COMPOUND) || !tag.contains("State",Tag.TAG_COMPOUND)) return Optional.empty();
            CompoundTag p=tag.getCompound("Plan"),s=tag.getCompound("State");
            requireBinding(p);requireBinding(s);require(p,"Calibration",Tag.TAG_COMPOUND);
            CompoundTag c=p.getCompound("Calibration");
            Calibration calibration=new Calibration(bool(c,"LiveVerified"),number(c,"BrakingDeceleration"),number(c,"MinimumTurnRadius"),
                    number(c,"MaximumSteeringDegrees"),integer(c,"NeutralThrottleSignal"),integer(c,"FullThrottleSignal"));
            Plan plan=new Plan(p.getUUID("MissionId"),p.getUUID("PhysicalId"),p.getString("Dimension"),p.getLong("LayoutRevision"),
                    p.getString("RunwayId"),p.getString("StandId"),p.getString("ReservationId"),readPoints(p,"Outbound"),integer(p,"HoldPointIndex"),readPoints(p,"Inbound"),
                    number(p,"RunwayYaw"),number(p,"StandYaw"),number(p,"SpeedLimit"),number(p,"CorridorHalfWidth"),calibration);
            require(s,"Phase",Tag.TAG_STRING);require(s,"LastObservationTick",Tag.TAG_LONG);bool(s,"Recovered");
            State state=new State(s.getUUID("MissionId"),s.getUUID("PhysicalId"),s.getString("Dimension"),s.getLong("LayoutRevision"),
                    s.getString("RunwayId"),s.getString("StandId"),s.getString("ReservationId"),Phase.valueOf(s.getString("Phase")),
                    integer(s,"PointIndex"),integer(s,"StableTicks"),s.getLong("LastObservationTick"),s.getBoolean("Recovered"),
                    s.contains("LastThrottleSignal") ? integer(s,"LastThrottleSignal") : -1);
            if (!AircraftGroundMission.validPlan(plan) || !validState(plan,state)) return Optional.empty();
            return Optional.of(new Saved(plan,recovered(state)));
        } catch (IllegalArgumentException | IllegalStateException malformed) { return Optional.empty(); }
    }

    private static boolean validState(Plan p,State s) {
        if (s==null || s.phase()==null || !Objects.equals(p.missionId(),s.missionId()) || !Objects.equals(p.physicalId(),s.physicalId())
                || !Objects.equals(p.dimensionId(),s.dimensionId()) || p.layoutRevision()!=s.layoutRevision() || !Objects.equals(p.runwayId(),s.runwayId())
                || !Objects.equals(p.standId(),s.standId()) || !Objects.equals(p.reservationId(),s.reservationId())
                || s.pointIndex()<0 || s.stableTicks()<0 || s.stableTicks()>40 || s.lastObservationTick() < -1 || !AircraftGroundMission.validThrottleLatch(p,s.lastThrottleSignal())) return false;
        int max=switch(s.phase()) {
            case TAXI_OUT,HOLD_LINE -> p.holdPointIndex();
            case LINE_UP,FC_STARTING,FC_ACTIVE -> p.outbound().size()-1;
            case WAIT_FC_RELEASE -> 0;
            case TAXI_IN,STAND_STABILIZING,COMPLETE -> p.inbound().size()-1;
            case NEEDS_REVIEW -> Math.max(p.outbound().size(),p.inbound().size())-1;
        };
        return s.pointIndex()<=max;
    }
    private static void binding(CompoundTag t,UUID mission,UUID physical,String dimension,long revision,String runway,String stand,String reservation) {
        t.putUUID("MissionId",mission);t.putUUID("PhysicalId",physical);t.putString("Dimension",dimension);t.putLong("LayoutRevision",revision);
        t.putString("RunwayId",runway);t.putString("StandId",stand);t.putString("ReservationId",reservation);
    }
    private static void requireBinding(CompoundTag t) {
        if (!t.hasUUID("MissionId") || !t.hasUUID("PhysicalId")) throw new IllegalArgumentException("missing identity");
        require(t,"Dimension",Tag.TAG_STRING);require(t,"LayoutRevision",Tag.TAG_LONG);require(t,"RunwayId",Tag.TAG_STRING);
        require(t,"StandId",Tag.TAG_STRING);require(t,"ReservationId",Tag.TAG_STRING);
    }
    private static ListTag points(List<Vec3> points) {
        ListTag tag=new ListTag();for(Vec3 point:points) { CompoundTag p=new CompoundTag();p.putDouble("X",point.x);p.putDouble("Y",point.y);p.putDouble("Z",point.z);tag.add(p); }return tag;
    }
    private static List<Vec3> readPoints(CompoundTag t,String key) {
        require(t,key,Tag.TAG_LIST);ListTag points=t.getList(key,Tag.TAG_COMPOUND);
        if(points.size()<2 || points.size()>256) throw new IllegalArgumentException("invalid point count");
        List<Vec3> result=new ArrayList<>(points.size());for(int i=0;i<points.size();i++) {CompoundTag p=points.getCompound(i);result.add(new Vec3(number(p,"X"),number(p,"Y"),number(p,"Z")));}return result;
    }
    private static void require(CompoundTag t,String key,int type) { if(!t.contains(key,type))throw new IllegalArgumentException("missing or invalid "+key); }
    private static double number(CompoundTag t,String key) {require(t,key,Tag.TAG_DOUBLE);return t.getDouble(key);}
    private static int integer(CompoundTag t,String key) {require(t,key,Tag.TAG_INT);return t.getInt(key);}
    private static boolean bool(CompoundTag t,String key) {require(t,key,Tag.TAG_BYTE);return t.getBoolean(key);}
}
