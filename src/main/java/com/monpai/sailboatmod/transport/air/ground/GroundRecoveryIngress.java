package com.monpai.sailboatmod.transport.air.ground;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** An explicitly validated, fixed first-segment ingress. Never rewrites the captured mission. */
public final class GroundRecoveryIngress {
    private static final double EPS=1e-7;
    private GroundRecoveryIngress() { }
    public static final class Candidate {
        private final AircraftGroundMission.Plan plan;
        private final Vec3 start,uxz;
        private final double minimumAlong,width;
        private final int height;
        private final TaxiRouteGeometry.Request geometry;
        private final Set<ChunkPos> footprint;
        private Candidate(AircraftGroundMission.Plan plan,Vec3 start,Vec3 direction,double along,double width,int height,
                          TaxiRouteGeometry.Request geometry,Set<ChunkPos> footprint){
            this.plan=plan;this.start=start;this.uxz=direction;this.minimumAlong=along;this.width=width;this.height=height;
            this.geometry=geometry;this.footprint=Collections.unmodifiableSet(new LinkedHashSet<>(footprint));
        }
        public Vec3 scanStart(){return start;}
        public Vec3 scanEnd(){return plan.outbound().getFirst();}
        public double width(){return width;}
        public int clearanceHeight(){return height;}
        public TaxiRouteGeometry.Request validationRequest(){return geometry;}
        public Set<ChunkPos> footprint(){return footprint;}
        public AircraftGroundMission.Plan plan(){return plan;}
    }
    public static final class Proof {
        private final Candidate candidate;
        private Proof(Candidate candidate){this.candidate=candidate;}
    }
    public record Evidence(boolean sameBinding,boolean freshStoppedSupported,boolean terrainAndSupportClear,
                           boolean claimsUnchanged,boolean fullyLoaded) { }
    public static Candidate candidate(AircraftGroundMission.Plan plan,AircraftGroundMission.State state,
                                      Vec3 position,Vec3 forward,double hullRadius,double hullHeight){
        if(!AircraftGroundMission.validPlan(plan)||!bound(plan,state)||!finite(position)||!finite(forward)
                ||forward.horizontalDistanceSqr()<1e-8||!positive(hullRadius)||!positive(hullHeight)
                ||plan.outbound().size()>63||!plan.calibration().liveVerified())return null;
        Vec3 first=plan.outbound().getFirst(),delta=horizontal(plan.outbound().get(1).subtract(first));
        double length=delta.length();if(!Double.isFinite(length)||length<.1||length>256)return null;
        Vec3 u=delta.scale(1/length),offset=horizontal(position.subtract(first));
        double along=offset.dot(u),lateral=Math.abs(offset.x*u.z-offset.z*u.x);
        if(!Double.isFinite(along)||along>=-EPS||along< -length-EPS||lateral>plan.corridorHalfWidth()+EPS
                ||horizontal(forward).dot(u)<=0)return null;
        double width=2*(hullRadius+plan.corridorHalfWidth());int height=(int)Math.ceil(Math.max(4,hullHeight));
        if(!Double.isFinite(width)||width<2||width>64||height>64)return null;
        // Capture a fixed five-centimeter longitudinal allowance once; never grow it on later samples.
        double minimumAlong=Math.max(-length,along-.05);
        Vec3 projected=first.add(u.scale(minimumAlong));
        List<TaxiRouteGeometry.Point> points=new ArrayList<>();points.add(new TaxiRouteGeometry.Point(projected.x,projected.z));
        for(Vec3 p:plan.outbound())points.add(new TaxiRouteGeometry.Point(p.x,p.z));
        var c=plan.calibration();
        var request=new TaxiRouteGeometry.Request(points,width,height,plan.holdPointIndex()+1,
                new TaxiRouteGeometry.Point(position.x,position.z),Math.toDegrees(Math.atan2(-forward.x,forward.z)),plan.runwayYawDegrees(),
                new TaxiRouteGeometry.Capabilities(c.liveVerified(),hullRadius,hullHeight,c.minimumTurnRadius(),c.brakingDeceleration(),plan.speedLimit()));
        if(!TaxiRouteGeometry.assess(request).admitted())return null;
        AABB bounds=new AABB(Math.min(projected.x,first.x)-width/2,first.y,Math.min(projected.z,first.z)-width/2,
                Math.max(projected.x,first.x)+width/2,first.y+height,Math.max(projected.z,first.z)+width/2);
        if(Math.max(Math.abs(bounds.minX),Math.abs(bounds.maxX))>30_000_000||Math.max(Math.abs(bounds.minZ),Math.abs(bounds.maxZ))>30_000_000)return null;
        Set<ChunkPos> footprint=new LinkedHashSet<>();
        for(int x=(int)Math.floor(bounds.minX)>>4;x<=((int)Math.floor(bounds.maxX)>>4);x++)
            for(int z=(int)Math.floor(bounds.minZ)>>4;z<=((int)Math.floor(bounds.maxZ)>>4);z++){
                if(footprint.size()>=512)return null;footprint.add(new ChunkPos(x,z));
            }
        return new Candidate(plan,projected,u,minimumAlong,width,height,request,footprint);
    }
    public static Proof authorize(Candidate candidate,Evidence evidence){
        return candidate!=null&&evidence!=null&&evidence.sameBinding()&&evidence.freshStoppedSupported()
                &&evidence.terrainAndSupportClear()&&evidence.claimsUnchanged()&&evidence.fullyLoaded()?new Proof(candidate):null;
    }
    public static boolean allows(Proof proof,AircraftGroundMission.Plan plan,AircraftGroundMission.State state,
                                 Vec3 position,Vec3 forward){
        if(proof==null||proof.candidate.plan!=plan||!bound(plan,state)||!finite(position)||!finite(forward)
                ||forward.horizontalDistanceSqr()<1e-8||horizontal(forward).dot(proof.candidate.uxz)<=0)return false;
        Candidate c=proof.candidate;Vec3 offset=horizontal(position.subtract(plan.outbound().getFirst()));
        double along=offset.dot(c.uxz),lateral=Math.abs(offset.x*c.uxz.z-offset.z*c.uxz.x);
        return along>=c.minimumAlong-EPS&&along<0&&lateral<=plan.corridorHalfWidth()+EPS;
    }
    public static boolean completed(Proof proof,Vec3 position){
        if(proof==null||!finite(position))return false;
        Candidate c=proof.candidate;Vec3 first=c.plan.outbound().getFirst(),delta=horizontal(position.subtract(first));
        double along=delta.dot(c.uxz),length=horizontal(c.plan.outbound().get(1).subtract(first)).length();
        return along>=0&&along<=length&&Math.abs(delta.x*c.uxz.z-delta.z*c.uxz.x)<=c.plan.corridorHalfWidth()+EPS;
    }
    private static boolean bound(AircraftGroundMission.Plan p,AircraftGroundMission.State s){
        return p!=null&&s!=null&&s.phase()==AircraftGroundMission.Phase.TAXI_OUT&&s.pointIndex()==1&&p.holdPointIndex()>=1
                &&Objects.equals(p.missionId(),s.missionId())&&Objects.equals(p.physicalId(),s.physicalId())
                &&Objects.equals(p.dimensionId(),s.dimensionId())&&p.layoutRevision()==s.layoutRevision()
                &&Objects.equals(p.runwayId(),s.runwayId())&&Objects.equals(p.standId(),s.standId())&&Objects.equals(p.reservationId(),s.reservationId());
    }
    private static Vec3 horizontal(Vec3 p){return new Vec3(p.x,0,p.z);}
    private static boolean finite(Vec3 p){return p!=null&&Double.isFinite(p.x)&&Double.isFinite(p.y)&&Double.isFinite(p.z);}
    private static boolean positive(double v){return Double.isFinite(v)&&v>0;}
}
