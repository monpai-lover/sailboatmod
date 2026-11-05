package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.route.water.local.BoatCollisionField;
import com.monpai.sailboatmod.route.water.local.CoastalClearance;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Worker-only refinement of a private voyage. Public lanes and exact endpoints are immutable.
 * Cruise centres use the symmetric 5x5 water test. Exact start/end positions use the
 * standard two-block physical footprint, not a 3x3 box snapped half a block away.
 * Each endpoint connects to a verified cruise anchor within three blocks. Only that
 * final short arrival segment may enter the reserve; all segments retain physical checks.
 * Unknown, wrong-height water and actual land remain failures, never fallback water.
 */
public final class ShoreRouteRefiner {
    private static final int MAX_SAMPLES=180_000, MAX_POINTS=8192, MAX_REPAIRS=64;
    private static final double TRANSITION=3.0, EPS=1e-7;
    private final RealBlockWaterMap map;
    private final WaterRoutePostProcessor.AwaitTerrain await;
    private final long deadline;
    private final Vec3 source, destination;
    private final int waterY;
    private final Map<Long,WaterColumn> raw=new HashMap<>(), paddedCache=new HashMap<>();
    private final WaterRouteWorld cruise;
    private final BoatCollisionField field;
    private int samples, repairs, moved;
    private String stage="INPUT", reason="UNFINISHED", detail="none", observation="none";

    private ShoreRouteRefiner(RealBlockWaterMap map,WaterRoutePostProcessor.AwaitTerrain await,
                              long deadline,Vec3 source,Vec3 destination) {
        this.map=map;this.await=await;this.deadline=deadline;
        this.source=source;this.destination=destination;
        waterY=(int)Math.round(source.y);
        cruise=new WaterRouteWorld() {
            public WaterColumn sample(int x,int z,WaterRoutePolicy policy){return padded(x,z);}
            public boolean canLoadMoreChunks(int n){return false;}
            public int consumedChunkLoads(){return 0;}
        };
        // Geometry is built only from the existing asynchronous column map. No world reads.
        var hull=new BoatCollisionField.Hull(1,1,waterY-.22,waterY+2,waterY-1);
        field=new BoatCollisionField(hull,(x,z)->{
            var c=one(x,z);
            if(c==null)return BoatCollisionField.Cell.unknown();
            boolean water=c.passable()&&c.surfacePos()!=null&&c.surfacePos().getY()==waterY;
            return new BoatCollisionField.Cell(true,water,List.of());
        },List.of());
    }

    public static List<Vec3> refine(List<Vec3> path,RealBlockWaterMap map,
                                     WaterRoutePostProcessor.AwaitTerrain await,long deadline) {
        if(path==null||path.size()<2||path.size()>65_536||map==null
                ||!finite(path.getFirst())||!finite(path.getLast())) {
            System.getLogger("Sailboat ShoreClearance").log(System.Logger.Level.WARNING,
                    "[navfix4-shore] output=0 stage=INPUT reason=INVALID_INPUT");
            return List.of();
        }
        var r=new ShoreRouteRefiner(map,await,deadline,path.getFirst(),path.getLast());
        List<Vec3> result=List.of();
        try {
            result=r.run(path);
            return result;
        } catch(StrictWaterDetour.Failure failure) {
            r.reason=failure.reason().name();r.detail=r.observation;
            throw failure; // Keep existing cancellation, budget and timeout behavior.
        } finally {
            System.getLogger("Sailboat ShoreClearance").log(System.Logger.Level.INFO,
                "[navfix4-shore] input="+path.size()+" output="+result.size()+" stage="+r.stage
                +" reason="+r.reason+" detail="+r.detail+" start="+r.source+" end="+r.destination
                +" waterY="+r.waterY+" moved="+r.moved+" repairedEdges="+r.repairs
                +" samples="+r.samples+" cruiseCentreMargin>=2; exactEndpointTransition<=3");
        }
    }

    private static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
    private WaterColumn one(int x,int z) {
        StrictWaterDetour.checkDeadline(deadline);
        long k=key(x,z);WaterColumn c=raw.get(k);
        if(c==null){
            if(++samples>MAX_SAMPLES)throw new StrictWaterDetour.Failure(
                    WaterRouteFailureReason.NODE_BUDGET_EXCEEDED,"Shore column budget");
            c=map.sampleHullStrict(x,z,0);
            if(c!=null)raw.put(k,c); // Unknown columns must be retried, never cached as land.
        }
        if(c==null)observation="unknown@("+x+","+z+")";
        else if(!c.passable()||c.surfacePos()==null)observation="dry_or_occupied@("+x+","+z+")";
        else if(c.surfacePos().getY()!=waterY)observation="water_height@("+x+","+z+")="
                +c.surfacePos().getY()+",expected="+waterY;
        return c;
    }

    private WaterColumn padded(int x,int z) {
        StrictWaterDetour.checkDeadline(deadline);
        long k=key(x,z);var old=paddedCache.get(k);if(old!=null)return old;
        boolean pending=false;
        for(int r=0;r<=2;r++)for(int dz=-r;dz<=r;dz++)for(int dx=-r;dx<=r;dx++) {
            if(Math.max(Math.abs(dx),Math.abs(dz))!=r)continue;
            var c=one(x+dx,z+dz);
            if(c==null){pending=true;continue;}
            if(!c.passable()||c.surfacePos()==null||c.surfacePos().getY()!=waterY) {
                var no=WaterColumn.blocked();paddedCache.put(k,no);return no;
            }
        }
        if(pending)return null;
        var yes=WaterColumn.passable(new BlockPos(x,waterY,z),0);
        paddedCache.put(k,yes);return yes;
    }

    private BoatCollisionField.Check segment(Vec3 a,Vec3 b,boolean arrival) {
        // Arrival never bypasses the physical hull, and cannot relax a long or middle edge.
        return field.navfix3$segment(a,b,arrival?0:CoastalClearance.MIN_CENTRE_CLEARANCE);
    }
    private BoatCollisionField.Check awaitSegment(Vec3 a,Vec3 b,boolean arrival) {
        while(true) {
            StrictWaterDetour.checkDeadline(deadline);
            var c=segment(a,b,arrival);
            if(c!=BoatCollisionField.Check.UNKNOWN)return c;
            StrictWaterDetour.waitTerrain(await,deadline);
        }
    }
    private boolean physicalEndpoint(Vec3 point,String side) {
        stage=side+"_FOOTPRINT";
        if(awaitSegment(point,point,true)!=BoatCollisionField.Check.CLEAR) {
            reason=side+"_PHYSICAL_BLOCKED";detail=observation;return false;
        }
        return true;
    }

    /** Never move an endpoint. Find a separate cruise anchor and validate its exact connector. */
    private BlockPos anchor(Vec3 endpoint,boolean arrival) {
        stage=arrival?"END_APPROACH":"START_DEPARTURE";
        List<BlockPos> candidates=candidates(endpoint,TRANSITION);
        while(true) {
            StrictWaterDetour.checkDeadline(deadline);boolean pending=false;
            for(var p:candidates) {
                var c=padded(p.getX(),p.getZ());
                if(c==null){pending=true;continue;}
                if(!c.passable())continue;
                Vec3 centre=Vec3.atBottomCenterOf(p);
                var check=arrival?segment(centre,endpoint,true):segment(endpoint,centre,false);
                if(check==BoatCollisionField.Check.UNKNOWN){pending=true;continue;}
                if(check==BoatCollisionField.Check.CLEAR)return p;
            }
            if(!pending){reason=arrival?"END_NO_SAFE_APPROACH":"START_NO_SAFE_DEPARTURE";
                detail="no_verified_cruise_anchor_within_3_blocks";return null;}
            StrictWaterDetour.waitTerrain(await,deadline);
        }
    }
    private List<BlockPos> candidates(Vec3 p,double radius) {
        int cx=(int)Math.floor(p.x),cz=(int)Math.floor(p.z),r=(int)Math.ceil(radius)+1;
        List<BlockPos> out=new ArrayList<>();
        for(int dz=-r;dz<=r;dz++)for(int dx=-r;dx<=r;dx++) {
            var q=new BlockPos(cx+dx,waterY,cz+dz);
            if(horizontal(p,Vec3.atBottomCenterOf(q))<=radius+EPS)out.add(q);
        }
        out.sort(Comparator.comparingDouble(q->horizontal(p,Vec3.atBottomCenterOf(q))));
        return out;
    }
    private BlockPos nearest(Vec3 point) {
        var options=candidates(point,6);
        while(true) {
            StrictWaterDetour.checkDeadline(deadline);boolean pending=false;
            for(var p:options){
                var c=padded(p.getX(),p.getZ());
                if(c==null){pending=true;continue;}
                if(c.passable()){
                    if(p.getX()!=(int)Math.floor(point.x)||p.getZ()!=(int)Math.floor(point.z))moved++;
                    return p;
                }
            }
            if(!pending){reason="NO_CRUISE_CLEARANCE";detail="near="+point;return null;}
            StrictWaterDetour.waitTerrain(await,deadline);
        }
    }
    private boolean append(List<BlockPos> safe,BlockPos next) {
        BlockPos previous=safe.getLast();if(previous.equals(next))return true;
        if(StrictWaterDetour.awaitEdge(cruise,previous,next,await,deadline)==StrictWaterDetour.Check.CLEAR)
            safe.add(next);
        else {
            if(++repairs>MAX_REPAIRS)throw new StrictWaterDetour.Failure(
                    WaterRouteFailureReason.NODE_BUDGET_EXCEEDED,"Shore repair budget");
            var detour=StrictWaterDetour.find(cruise,previous,next,await,deadline,6000);
            if(detour.isEmpty()){reason="NO_CRUISE_PATH";detail=previous+"->"+next;return false;}
            safe.addAll(detour.subList(1,detour.size()));
        }
        if(safe.size()>MAX_POINTS)throw new StrictWaterDetour.Failure(
                WaterRouteFailureReason.NODE_BUDGET_EXCEEDED,"Shore output budget");
        return true;
    }

    private List<Vec3> run(List<Vec3> path) {
        // Height semantics remain water/air interface, not floating entity Y.
        if(Math.abs(source.y-waterY)>1e-6){reason="NON_INTERFACE_HEIGHT";detail="sourceY="+source.y;return List.of();}
        List<Vec3> dense=new ArrayList<>();dense.add(source);
        for(int i=1;i<path.size();i++) {
            Vec3 a=path.get(i-1),b=path.get(i);
            if(!finite(a)||!finite(b)||Math.abs(a.y-waterY)>1e-6||Math.abs(b.y-waterY)>1e-6){
                reason="ROUTE_HEIGHT_MISMATCH";detail="index="+i;return List.of();}
            int steps=Math.max(1,(int)Math.ceil(horizontal(a,b)/12));
            if(dense.size()+(long)steps>MAX_POINTS)throw new StrictWaterDetour.Failure(
                    WaterRouteFailureReason.NODE_BUDGET_EXCEEDED,"Shore input budget");
            for(int k=1;k<=steps;k++)dense.add(a.lerp(b,k/(double)steps));
        }
        if(!physicalEndpoint(source,"START")||!physicalEndpoint(destination,"END"))return List.of();
        BlockPos start=anchor(source,false);if(start==null)return List.of();
        BlockPos end=anchor(destination,true);if(end==null)return List.of();
        // Remove only contiguous endpoint neighbourhoods, never arbitrary mid-voyage points.
        int first=1,last=dense.size()-2;
        while(first<=last&&horizontal(dense.get(first),source)<=TRANSITION)first++;
        while(last>=first&&horizontal(dense.get(last),destination)<=TRANSITION)last--;
        stage="CRUISE";List<BlockPos> safe=new ArrayList<>();safe.add(start);
        for(int i=first;i<=last;i++) {
            BlockPos next=nearest(dense.get(i));if(next==null||!append(safe,next))return List.of();
        }
        if(!append(safe,end))return List.of();
        List<BlockPos> pulled=new ArrayList<>();pulled.add(start);
        for(int i=0;i<safe.size()-1;) {
            StrictWaterDetour.checkDeadline(deadline);int chosen=i+1;
            for(int j=Math.min(safe.size()-1,i+16);j>i+1;j--)
                if(StrictWaterDetour.edge(cruise,safe.get(i),safe.get(j))==StrictWaterDetour.Check.CLEAR){chosen=j;break;}
            pulled.add(safe.get(chosen));i=chosen;
        }
        List<Vec3> result=new ArrayList<>();result.add(source);
        for(var p:pulled)add(result,Vec3.atBottomCenterOf(p));
        if(result.getLast().distanceToSqr(destination)>EPS*EPS)result.add(destination);
        else result.set(result.size()-1,destination); // Preserve the exact terminal coordinate.
        if(result.size()<2){reason="DEGENERATE_ROUTE";return List.of();}
        // Grid checks alone cannot validate sub-block endpoints. Sweep the returned edges too.
        stage="FINAL_SWEEP";
        for(int i=1;i<result.size();i++) {
            Vec3 a=result.get(i-1),b=result.get(i);
            boolean arrival=i==result.size()-1&&horizontal(a,destination)<=TRANSITION+EPS;
            // Bound the swept geometry without changing the returned path's vertices.
            int pieces=Math.max(1,(int)Math.ceil(horizontal(a,b)/16));
            for(int k=0;k<pieces;k++) {
                if(awaitSegment(a.lerp(b,k/(double)pieces),a.lerp(b,(k+1)/(double)pieces),arrival)
                        !=BoatCollisionField.Check.CLEAR) {
                    reason="EXACT_SEGMENT_BLOCKED";detail="edge="+i+","+observation;return List.of();
                }
            }
        }
        stage="COMPLETE";reason="OK";detail="none";return List.copyOf(result);
    }
    private static void add(List<Vec3> out,Vec3 p){if(out.isEmpty()||out.getLast().distanceToSqr(p)>EPS*EPS)out.add(p);}
    private static double horizontal(Vec3 a,Vec3 b){return Math.hypot(a.x-b.x,a.z-b.z);}
    private static boolean finite(Vec3 p){return p!=null&&Double.isFinite(p.x)&&Double.isFinite(p.y)&&Double.isFinite(p.z);}
}
