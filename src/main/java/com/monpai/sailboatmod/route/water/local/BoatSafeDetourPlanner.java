package com.monpai.sailboatmod.route.water.local;
import com.monpai.sailboatmod.route.WaterPolylineFollower;
import com.monpai.sailboatmod.route.water.*;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Loaded-snapshot-only A*: identical clearance during search, pulling and execution.
 * This does not load chunks, move blocks or publish a new persistent route.
 */
public final class BoatSafeDetourPlanner {
 private record Target(Vec3 point,int tail){}
 private record Node(int x,int z,double cost,double score,Node parent){}
 private final BoatCollisionField field;
 private final Vec3 destination;
 private record Edge(Vec3 a,Vec3 b){}
 private final Map<Edge,BoatCollisionField.Check> edges=new HashMap<>();
 private boolean unknown;
 private int budget=10_000;
 private BoatSafeDetourPlanner(BoatCollisionField f,Vec3 destination){field=f;this.destination=destination;}
 private BoatCollisionField.Check check(Vec3 a,Vec3 b){
  var key=new Edge(a,b);var cached=edges.get(key);if(cached!=null){unknown|=cached==BoatCollisionField.Check.UNKNOWN;return cached;}
  var value=field.navfix3$segment(a,b,WaterPolylineFollower.horizontal(a,destination)<=3&&WaterPolylineFollower.horizontal(b,destination)<=3?0:CoastalClearance.MIN_CENTRE_CLEARANCE + .55);
  unknown|=value==BoatCollisionField.Check.UNKNOWN;if(edges.size()<40_000)edges.put(key,value);return value;
 }
 public static WaterRouteResult<WaterRescuePlanner.Splice> plan(BoatCollisionField f,Vec3 position,List<Vec3> route,int index){
  if(route==null||route.size()<2||index<0||index>=route.size())return WaterRouteResult.failure(WaterRouteFailureReason.NO_WATER_PATH);
  var p=new BoatSafeDetourPlanner(f,route.getLast());Vec3 start=new Vec3(position.x,route.get(index).y,position.z);
  for(Target target:targets(start,route,index)){
   WaterRouteCancellation.check();if(p.budget<=0)break;
   if(target.tail<route.size()){
    Vec3 next=route.get(target.tail);double d=WaterPolylineFollower.horizontal(target.point,next);
    Vec3 end=target.point.lerp(next,d<1e-8?1:Math.min(1,6/d));
    if(p.check(target.point,end)!=BoatCollisionField.Check.CLEAR)continue;
   }
   var path=p.search(start,target.point);
   if(!path.isEmpty())return WaterRouteResult.success(new WaterRescuePlanner.Splice(path,target.tail));
  }
  return WaterRouteResult.failure(p.unknown?WaterRouteFailureReason.TERRAIN_NOT_READY:WaterRouteFailureReason.NO_WATER_PATH);
 }
 private static List<Target> targets(Vec3 start,List<Vec3> route,int index){
  List<Target> out=new ArrayList<>();double walked=0;
  for(int i=index;i<route.size()&&i<index+32&&walked<=48;i++){
   Vec3 begin=i==0?start:route.get(i-1),end=route.get(i);
   Vec3 on=i==index?begin.lerp(end,Math.clamp(WaterPolylineFollower.project(start,begin,end).t(),0,1)):begin;
   double len=WaterPolylineFollower.horizontal(on,end);
   for(double forward:new double[]{8,16,24,32,40}){double left=forward-walked;if(left>0&&left<len){Vec3 v=on.lerp(end,left/len);if(inWindow(start,v))out.add(new Target(v,i));}}
   if(len+walked>=2&&inWindow(start,end))out.add(new Target(end,i+1));walked+=len;
  }
  out.sort(Comparator.comparingDouble((Target t)->WaterPolylineFollower.horizontal(start,t.point)).reversed());return out.stream().distinct().limit(12).toList();
 }
 private static boolean inWindow(Vec3 a,Vec3 b){return Math.max(Math.abs(a.x-b.x),Math.abs(a.z-b.z))<=20;}
 private List<Vec3> search(Vec3 start,Vec3 goal){
  if(check(start,goal)==BoatCollisionField.Check.CLEAR)return List.of(start,goal);
  int r=21,side=2*r+1;double[] best=new double[side*side];Arrays.fill(best,Double.POSITIVE_INFINITY);
  var open=new PriorityQueue<Node>(Comparator.comparingDouble(Node::score).thenComparingInt(Node::x).thenComparingInt(Node::z));
  open.add(new Node(0,0,0,WaterPolylineFollower.horizontal(start,goal),null));best[r*side+r]=0;
  while(!open.isEmpty()&&budget>0){
   WaterRouteCancellation.check();Node n=open.poll();if(n.cost>best[(n.z+r)*side+n.x+r]+1e-8)continue;
   budget--;Vec3 at=start.add(n.x,0,n.z);
   if(WaterPolylineFollower.horizontal(at,goal)<=1.5&&check(at,goal)==BoatCollisionField.Check.CLEAR){
    List<Vec3> path=new ArrayList<>();path.add(goal);for(Node t=n;t!=null;t=t.parent)path.add(start.add(t.x,0,t.z));Collections.reverse(path);return pull(path);
   }
   for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++){
    if(dx==0&&dz==0)continue;int x=n.x+dx,z=n.z+dz;if(Math.abs(x)>r||Math.abs(z)>r)continue;
    int dest=(z+r)*side+x+r;double cost=n.cost+Math.hypot(dx,dz);if(cost>=best[dest]-1e-8)continue;
    Vec3 next=start.add(x,0,z);if(check(at,next)!=BoatCollisionField.Check.CLEAR)continue;
    best[dest]=cost;open.add(new Node(x,z,cost,cost+WaterPolylineFollower.horizontal(next,goal),n));
   }
  }
  return List.of();
 }
 private List<Vec3> pull(List<Vec3> raw){
  List<Vec3> out=new ArrayList<>();out.add(raw.getFirst());
  for(int i=0;i<raw.size()-1;){
   WaterRouteCancellation.check();int chosen=i+1;
   for(int j=raw.size()-1;j>i+1;j--)if(check(raw.get(i),raw.get(j))==BoatCollisionField.Check.CLEAR){chosen=j;break;}
   if(WaterPolylineFollower.horizontal(out.getLast(),raw.get(chosen))>1e-8)out.add(raw.get(chosen));i=chosen;
  }
  return List.copyOf(out);
 }
}
