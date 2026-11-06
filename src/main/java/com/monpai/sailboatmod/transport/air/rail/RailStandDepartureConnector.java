package com.monpai.sailboatmod.transport.air.rail;

import com.monpai.sailboatmod.transport.air.airport.AirportParkingStand;
import com.monpai.sailboatmod.transport.air.airport.AirportTaxiRoute;
import java.util.*;
import java.util.function.Function;
import net.minecraft.world.phys.Vec3;

/** A bounded in-stand start search; only the compiler's actual certified path can leave this helper. */
public final class RailStandDepartureConnector {
    public static final int MAX_CANDIDATES=64;
    private static final int MAX_DEPTH=12,MAX_PROOF_NODES=4096;
    private static final double EPS=1e-8;
    public record Connection(List<Vec3> prefixPoints,List<Vec3> remainingOutboundPoints,int holdPointIndex,double joinFraction) {
        public Connection { prefixPoints=List.copyOf(prefixPoints);remainingOutboundPoints=List.copyOf(remainingOutboundPoints); }
    }
    public record Result(Optional<RailAircraftPath> path,String reason) { }
    private RailStandDepartureConnector() { }

    /** Coordinates are world coordinates at actualAnchor.y, including the unmodified remaining route nodes. */
    public static Result connect(AirportParkingStand stand,Vec3 actualAnchor,Vec3 actualForward,AirportTaxiRoute outbound,
                                 double sweepRadius,double cornerRadius,Function<Connection,RailAircraftPath> compiler) {
        if(!valid(stand,actualAnchor,actualForward,outbound,sweepRadius,cornerRadius)||compiler==null)return rejected();
        Vec3 a=outbound.points().getFirst(),b=outbound.points().get(1);
        Vec3 direction=new Vec3(b.x-a.x,0,b.z-a.z).normalize();double length=b.subtract(a).horizontalDistance();
        Vec3 forward=new Vec3(actualForward.x,0,actualForward.z).normalize();
        Region region=new Region(stand,a,b,sweepRadius,outbound.width()/2-sweepRadius,actualAnchor.y);
        if(!region.contains(actualAnchor))return rejected();
        double projection=(actualAnchor.x-a.x)*direction.x+(actualAnchor.z-a.z)*direction.z;
        SortedSet<Double> joins=new TreeSet<>();
        for(double advance:new double[]{1,2,4,6,8,12})addJoin(joins,Math.max(0,projection)+advance,length);
        for(double fraction:new double[]{.25,.5,.75})addJoin(joins,length*fraction,length);
        int attempts=0;
        for(double along:joins) {
            Vec3 join=new Vec3(a.x+direction.x*along,actualAnchor.y,a.z+direction.z*along);
            double distance=join.distanceTo(actualAnchor);
            for(double factor:new double[]{.5,1,1.5}) {
                double handle=Math.max(.25,Math.min(2,distance/3))*factor;
                Vec3 lead=actualAnchor.add(forward.scale(handle)),tail=join.subtract(direction.scale(Math.min(handle,along*.4)));
                List<Vec3> prefix=List.of(actualAnchor,lead,tail,join);
                if(!usable(prefix)||!region.contains(lead)||!region.contains(tail))continue;
                if(attempts++>=MAX_CANDIDATES)return rejected();
                List<Vec3> remaining=new ArrayList<>();remaining.add(join);
                for(int i=1;i<outbound.points().size();i++) {
                    Vec3 point=outbound.points().get(i);remaining.add(new Vec3(point.x,actualAnchor.y,point.z));
                }
                Connection connection=new Connection(prefix,remaining,outbound.holdPointIndex(),along/length);
                final RailAircraftPath compiled;
                try { compiled=compiler.apply(connection); }
                catch(RuntimeException|LinkageError unsupported){continue;}
                if(certifies(compiled,connection,forward,direction,region))return new Result(Optional.of(compiled),"");
            }
        }
        return rejected();
    }

    private static boolean certifies(RailAircraftPath path,Connection connection,Vec3 forward,Vec3 routeDirection,Region region) {
        if(path==null||path.legs().size()<2||path.legs().getFirst().phase()!=RailAircraftPath.Phase.TAXI_OUT
                ||!path.legs().getFirst().points().equals(connection.prefixPoints())
                ||!path.legs().get(1).points().getFirst().equals(connection.prefixPoints().getLast())
                ||path.legs().get(1).points().size()<2
                ||!path.legs().get(1).points().get(1).equals(connection.remainingOutboundPoints().get(1)))return false;
        var sample=path.sample(0);
        if(sample.position().distanceTo(connection.prefixPoints().getFirst())>EPS||sample.tangent().dot(forward)<1-1e-10)return false;
        int[] budget={MAX_PROOF_NODES};boolean found=false;RailAircraftPath.ControlHull last=null;
        for(var hull:path.controlPolygons()) {
            if(hull.legIndex()!=0)continue;
            found=true;last=hull;
            if(hull.phase()!=RailAircraftPath.Phase.TAXI_OUT||!prove(hull.points(),region,0,budget))return false;
        }
        if(!found||last==null)return false;
        var controls=last.points();
        return controls.getLast().equals(connection.prefixPoints().getLast())
                &&controls.getLast().subtract(controls.get(controls.size()-2)).normalize().dot(routeDirection)>1-1e-10;
    }

    /** A Bezier lies inside its control hull. Subdivision proves a union of two convex safe regions. */
    private static boolean prove(List<Vec3> controls,Region region,int depth,int[] budget) {
        if(--budget[0]<0)return false;
        boolean inStand=true,inRoute=true;
        for(Vec3 point:controls) {
            if(!finite(point)||Math.abs(point.y-region.height)>EPS)return false;
            inStand&=region.standContains(point);inRoute&=region.routeContains(point);
        }
        if(inStand||inRoute)return true;
        if(depth>=MAX_DEPTH||!region.contains(controls.getFirst())||!region.contains(controls.getLast()))return false;
        List<Vec3> work=new ArrayList<>(controls),left=new ArrayList<>(),right=new ArrayList<>();
        left.add(work.getFirst());right.add(work.getLast());
        for(int n=work.size()-1;n>0;n--) {
            for(int i=0;i<n;i++)work.set(i,work.get(i).lerp(work.get(i+1),.5));
            left.add(work.getFirst());right.add(work.get(n-1));
        }
        Collections.reverse(right);
        return prove(left,region,depth+1,budget)&&prove(right,region,depth+1,budget);
    }

    private record Region(AirportParkingStand stand,Vec3 from,Vec3 to,double radius,double routeMargin,double height) {
        boolean contains(Vec3 p){return standContains(p)||routeContains(p);}
        boolean standContains(Vec3 p) {
            double yaw=Math.toRadians(stand.yawDegrees()),dx=p.x-stand.position().x,dz=p.z-stand.position().z;
            return Math.abs(-Math.sin(yaw)*dx+Math.cos(yaw)*dz)<=stand.length()/2-radius
                    &&Math.abs(Math.cos(yaw)*dx+Math.sin(yaw)*dz)<=stand.width()/2-radius;
        }
        boolean routeContains(Vec3 p) {
            double dx=to.x-from.x,dz=to.z-from.z;
            double t=Math.max(0,Math.min(1,((p.x-from.x)*dx+(p.z-from.z)*dz)/(dx*dx+dz*dz)));
            return Math.hypot(p.x-from.x-t*dx,p.z-from.z-t*dz)<=routeMargin;
        }
    }
    private static boolean valid(AirportParkingStand stand,Vec3 anchor,Vec3 forward,AirportTaxiRoute route,double radius,double corner) {
        return stand!=null&&stand.validGeometry()&&stand.cargoEnabled()&&finite(anchor)&&finite(forward)&&forward.horizontalDistanceSqr()>EPS
                &&route!=null&&route.validGeometry()&&route.direction()==AirportTaxiRoute.Direction.OUTBOUND&&route.standId().equals(stand.id())
                &&route.holdPointIndex()>0&&route.points().getFirst().distanceTo(stand.position())<=1
                &&Math.abs(route.points().getFirst().y-stand.position().y)<EPS
                &&anchor.y>=stand.position().y&&anchor.y<=stand.position().y+stand.clearanceHeight()
                &&Double.isFinite(radius)&&radius>0&&radius<=32&&Double.isFinite(corner)&&corner>0
                &&route.width()/2-radius>=.5&&corner<=route.width()/2-radius
                &&route.points().get(1).subtract(route.points().getFirst()).horizontalDistance()>.5;
    }
    private static boolean usable(List<Vec3> points) {
        for(int i=1;i<points.size();i++) {
            Vec3 delta=points.get(i).subtract(points.get(i-1));
            if(delta.length()<.1)return false;
            if(i+1<points.size()&&delta.normalize().dot(points.get(i+1).subtract(points.get(i)).normalize())<-.95)return false;
        }
        return true;
    }
    private static void addJoin(Set<Double> joins,double distance,double length){if(distance>.5&&distance<length-.25)joins.add(distance);}
    private static boolean finite(Vec3 p){return p!=null&&Double.isFinite(p.x)&&Double.isFinite(p.y)&&Double.isFinite(p.z);}
    private static Result rejected(){return new Result(Optional.empty(),"rail_start_alignment_required");}
}
