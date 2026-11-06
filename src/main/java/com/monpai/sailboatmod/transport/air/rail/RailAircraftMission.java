package com.monpai.sailboatmod.transport.air.rail;

import com.monpai.sailboatmod.transport.air.airport.GroundAssignment;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import static com.monpai.sailboatmod.transport.air.rail.RailAircraftPath.*;

/** Server-tick journal only. Actual cargo and body motion remain with their existing owners. */
public final class RailAircraftMission {
    public record Step(double fromDistance,double targetDistance,RailAircraftPath.Sample sample,long revision) { }
    public static final int REQUIRED_STAND_TICKS=40;
    private static final int VERSION=1;
    private final UUID missionId,bodyId;
    private final String dimension;
    private final BlockPos source,target;
    private final GroundAssignment sourceAssignment,targetAssignment;
    private final RailAircraftPath path;
    private double distance;
    private long revision,lastObservationTick=-1;
    private boolean paused,completed;
    private String waitReason="";
    private int actualStandTicks;

    private RailAircraftMission(UUID missionId,UUID bodyId,String dimension,BlockPos source,GroundAssignment sourceAssignment,
                                BlockPos target,GroundAssignment targetAssignment,RailAircraftPath path) {
        if(missionId==null||bodyId==null||dimension==null||dimension.length()>256||!dimension.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")
                ||!validPosition(source)||!validPosition(target)||sourceAssignment==null||targetAssignment==null||path==null)
            throw invalid("invalid_rail_mission_binding");
        this.missionId=missionId;this.bodyId=bodyId;this.dimension=dimension;this.source=source.immutable();this.target=target.immutable();
        this.sourceAssignment=sourceAssignment;this.targetAssignment=targetAssignment;this.path=path;
    }
    public static RailAircraftMission start(UUID missionId,UUID bodyId,String dimension,BlockPos source,GroundAssignment sourceAssignment,
                                           BlockPos target,GroundAssignment targetAssignment,RailAircraftPath path) {
        return new RailAircraftMission(missionId,bodyId,dimension,source,sourceAssignment,target,targetAssignment,path);
    }
    public UUID missionId(){return missionId;}
    public UUID id(){return missionId;}
    public UUID bodyId(){return bodyId;}
    public String dimension(){return dimension;}
    public BlockPos source(){return source;}
    public BlockPos target(){return target;}
    public GroundAssignment sourceAssignment(){return sourceAssignment;}
    public GroundAssignment targetAssignment(){return targetAssignment;}
    public RailAircraftPath path(){return path;}
    public double distance(){return distance;}
    public boolean paused(){return paused;}
    public boolean completed(){return completed;}
    public String waitReason(){return waitReason;}
    public int actualStandTicks(){return actualStandTicks;}
    public RailAircraftPath.Phase phase(){return completed?RailAircraftPath.Phase.COMPLETE:atEnd()?RailAircraftPath.Phase.STAND_STABILIZING:path.sample(distance).phase();}

    /** A proposal is not progress: only a later actual body readback can authorize its commit. */
    public Step proposeStep(boolean movementAllowed) {
        double next=distance;
        if(!paused&&!completed&&waitReason.isEmpty()&&movementAllowed)
            next=Math.min(path.nextBoundary(distance),Math.min(path.length(),distance+path.speedForAdvance(distance)/20));
        RailAircraftPath.Sample sample=path.sample(next);
        if(next==distance)sample=new RailAircraftPath.Sample(sample.position(),sample.tangent(),sample.bankRadians(),sample.phase(),0,sample.pitchRadians());
        return new Step(distance,next,sample,revision);
    }
    public boolean commitStep(Step step,boolean actualPoseConfirmed) {
        if(!actualPoseConfirmed||step==null||step.sample()==null||step.revision()!=revision||Double.compare(step.fromDistance(),distance)!=0
                ||!finite(step.targetDistance())||step.targetDistance()<distance||step.targetDistance()>path.length())return false;
        Step permitted=proposeStep(true);
        if(step.targetDistance()!=distance&&Double.compare(step.targetDistance(),permitted.targetDistance())!=0)return false;
        RailAircraftPath.Sample expected=path.sample(step.targetDistance());
        if(step.targetDistance()==distance)expected=new RailAircraftPath.Sample(expected.position(),expected.tangent(),expected.bankRadians(),expected.phase(),0,expected.pitchRadians());
        if(!expected.position().equals(step.sample().position())||!expected.tangent().equals(step.sample().tangent())
                ||Double.compare(expected.bankRadians(),step.sample().bankRadians())!=0
                ||Double.compare(expected.pitchRadians(),step.sample().pitchRadians())!=0
                ||Double.compare(expected.speed(),step.sample().speed())!=0||expected.phase()!=step.sample().phase())return false;
        if(step.targetDistance()>distance){distance=step.targetDistance();resetStand();}
        bump();return true;
    }
    public void setPaused(boolean value){if(!completed&&paused!=value){paused=value;resetStand();bump();}}
    public void waitFor(String reason){if(reason==null||reason.isBlank()||reason.length()>256)throw invalid("invalid_wait_reason");if(!completed&&!waitReason.equals(reason)){waitReason=reason;resetStand();bump();}}
    public void clearWait(){if(!completed&&!waitReason.isEmpty()){waitReason="";resetStand();bump();}}

    /** Caller supplies a current actual hull/heading/speed/assignment/lease observation, once per running game tick. */
    public void observeStand(long gameTick,boolean actuallyParked) {
        if(gameTick<0)throw invalid("invalid_observation_tick");
        if(completed)return;
        if(gameTick<=lastObservationTick)return;
        boolean consecutive=lastObservationTick>=0&&gameTick-lastObservationTick==1;
        if(!atEnd()||paused||completed||!waitReason.isEmpty()||!actuallyParked)actualStandTicks=0;
        else actualStandTicks=consecutive?Math.min(REQUIRED_STAND_TICKS,actualStandTicks+1):1;
        lastObservationTick=gameTick;
    }
    public boolean readyToComplete(){return !completed&&atEnd()&&!paused&&waitReason.isEmpty()&&actualStandTicks==REQUIRED_STAND_TICKS;}
    public boolean complete(){if(!readyToComplete())return false;completed=true;bump();return true;}
    private boolean atEnd(){return Double.compare(distance,path.length())==0;}
    private void resetStand(){actualStandTicks=0;lastObservationTick=-1;}
    private void bump(){if(revision==Long.MAX_VALUE)throw new IllegalStateException("rail_revision_exhausted");revision++;}

    public CompoundTag save() {
        CompoundTag t=new CompoundTag();t.putInt("Version",VERSION);t.putUUID("MissionId",missionId);t.putUUID("BodyId",bodyId);t.putString("Dimension",dimension);
        t.putLong("Source",source.asLong());t.putLong("Target",target.asLong());t.put("SourceAssignment",sourceAssignment.save());t.put("TargetAssignment",targetAssignment.save());
        t.put("Path",path.save());t.putString("PathHash",path.fingerprint());t.putString("Phase",phase().name());t.putDouble("Distance",distance);
        t.putLong("Revision",revision);t.putBoolean("Paused",paused);t.putBoolean("Completed",completed);t.putString("WaitReason",waitReason);
        t.putInt("ActualStandTicks",actualStandTicks);t.putLong("LastObservationTick",lastObservationTick);return t;
    }
    public static RailAircraftMission load(CompoundTag t) {
        require(t,"Version",Tag.TAG_INT);if(t.getInt("Version")!=VERSION||!t.hasUUID("MissionId")||!t.hasUUID("BodyId"))throw invalid("invalid_rail_identity");
        require(t,"Dimension",Tag.TAG_STRING);require(t,"Source",Tag.TAG_LONG);require(t,"Target",Tag.TAG_LONG);require(t,"SourceAssignment",Tag.TAG_COMPOUND);require(t,"TargetAssignment",Tag.TAG_COMPOUND);
        require(t,"Path",Tag.TAG_COMPOUND);require(t,"PathHash",Tag.TAG_STRING);require(t,"Phase",Tag.TAG_STRING);require(t,"Distance",Tag.TAG_DOUBLE);require(t,"Revision",Tag.TAG_LONG);
        require(t,"Paused",Tag.TAG_BYTE);require(t,"Completed",Tag.TAG_BYTE);require(t,"WaitReason",Tag.TAG_STRING);require(t,"ActualStandTicks",Tag.TAG_INT);require(t,"LastObservationTick",Tag.TAG_LONG);
        RailAircraftPath path=RailAircraftPath.load(t.getCompound("Path"));if(!path.fingerprint().equals(t.getString("PathHash")))throw invalid("rail_path_shape_changed");
        RailAircraftMission m=start(t.getUUID("MissionId"),t.getUUID("BodyId"),t.getString("Dimension"),BlockPos.of(t.getLong("Source")),assignment(t.getCompound("SourceAssignment")),BlockPos.of(t.getLong("Target")),assignment(t.getCompound("TargetAssignment")),path);
        m.distance=t.getDouble("Distance");m.revision=t.getLong("Revision");m.paused=t.getBoolean("Paused");m.completed=t.getBoolean("Completed");m.waitReason=t.getString("WaitReason");
        int dwell=t.getInt("ActualStandTicks");long tick=t.getLong("LastObservationTick");
        if(!finite(m.distance)||m.distance<0||m.distance>path.length()||m.revision<0||m.revision==Long.MAX_VALUE||m.waitReason.length()>256
                ||dwell<0||dwell>REQUIRED_STAND_TICKS||tick< -1||dwell>0&&(!m.atEnd()||tick<0)
                ||m.completed&&(!m.atEnd()||dwell!=REQUIRED_STAND_TICKS||m.paused||!m.waitReason.isEmpty())
                ||!m.phase().name().equals(t.getString("Phase")))throw invalid("invalid_rail_progress");
        // Saved dwell is evidence of an old run, never fresh authorization to settle cargo after restart.
        if(m.completed){m.actualStandTicks=dwell;m.lastObservationTick=tick;}else m.resetStand();return m;
    }
    private static GroundAssignment assignment(CompoundTag t){require(t,"Revision",Tag.TAG_LONG);for(String key:List.of("Runway","Stand","Outbound","Inbound"))require(t,key,Tag.TAG_STRING);return GroundAssignment.load(t);}
    private static boolean validPosition(BlockPos p){return p!=null&&Math.abs((long)p.getX())<=30_000_000&&Math.abs((long)p.getZ())<=30_000_000&&p.getY()>=-2048&&p.getY()<=2047;}
}
