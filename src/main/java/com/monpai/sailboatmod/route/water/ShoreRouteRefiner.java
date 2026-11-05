package com.monpai.sailboatmod.route.water;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Ship destination menu: refine the private voyage, including the saved suffix.
 * Each cruising grid centre has a symmetric 5x5 water footprint (>=2 blocks from
 * a dry column). First/last three blocks use the centred physical 3x3 footprint.
 * All shortcuts and repairs use that same terrain. Unknown means await/fail, never water.
 * Worker-only: existing asynchronous map and deadline; no direct live-world access.
 */
public final class ShoreRouteRefiner {
 private static final int MAX_SAMPLES=180_000, MAX_POINTS=8192;
 private final RealBlockWaterMap map;
 private final WaterRoutePostProcessor.AwaitTerrain await;
 private final long deadline;
 private final Vec3 source,destination;
 private final Map<Long,WaterColumn> raw=new HashMap<>();
 private final Map<Long,WaterColumn> checked=new HashMap<>();
 private final WaterRouteWorld world;
 private int samples, repairs, moved;
 private ShoreRouteRefiner(RealBlockWaterMap m,WaterRoutePostProcessor.AwaitTerrain wait,long end,Vec3 a,Vec3 b){
  map=m;await=wait;deadline=end;source=a;destination=b;
  world=new WaterRouteWorld(){public WaterColumn sample(int x,int z,WaterRoutePolicy p){return padded(x,z);}public boolean canLoadMoreChunks(int n){return false;}public int consumedChunkLoads(){return 0;}};
 }
 public static List<Vec3> refine(List<Vec3> path,RealBlockWaterMap map,WaterRoutePostProcessor.AwaitTerrain wait,long deadline){
  if(path==null||path.size()<2||path.size()>65_536)return List.of();
  var refiner=new ShoreRouteRefiner(map,wait,deadline,path.getFirst(),path.getLast());
  List<Vec3> result=refiner.run(path);
  System.getLogger("Sailboat ShoreClearance").log(System.Logger.Level.INFO,
    "[navfix3-shore] input="+path.size()+" output="+result.size()+" moved="+refiner.moved+" repairedEdges="+refiner.repairs+" samples="+refiner.samples+" centreMargin>=2 blocks; endpoint transition=3 blocks");
  return result;
 }
 private static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
 private WaterColumn one(int x,int z){
  StrictWaterDetour.checkDeadline(deadline);long k=key(x,z);var c=raw.get(k);if(c!=null)return c;
  if(++samples>MAX_SAMPLES)throw new StrictWaterDetour.Failure(WaterRouteFailureReason.NODE_BUDGET_EXCEEDED,"Shore sample budget");
  c=map.sampleHullStrict(x,z,0);if(c!=null)raw.put(k,c);return c;
 }
 private WaterColumn padded(int x,int z){
  StrictWaterDetour.checkDeadline(deadline);long k=key(x,z);var old=checked.get(k);if(old!=null)return old;
  int radius=Math.hypot(x+.5-source.x,z+.5-source.z)<=3||Math.hypot(x+.5-destination.x,z+.5-destination.z)<=3?1:2;
  Integer y=null;boolean pending=false;
  for(int r=0;r<=radius;r++)for(int dz=-r;dz<=r;dz++)for(int dx=-r;dx<=r;dx++){
   if(Math.max(Math.abs(dx),Math.abs(dz))!=r)continue;
   var c=one(x+dx,z+dz);if(c==null){pending=true;continue;}
   if(!c.passable()||c.surfacePos()==null||(y!=null&&y!=c.surfacePos().getY())){var no=WaterColumn.blocked();checked.put(k,no);return no;}
   y=c.surfacePos().getY();
  }
  if(pending||y==null)return null;
  var result=WaterColumn.passable(new BlockPos(x,y,z),0);checked.put(k,result);return result;
 }
 private BlockPos nearest(Vec3 p){
  int x=(int)Math.floor(p.x),z=(int)Math.floor(p.z),y=(int)Math.round(p.y);
  List<int[]> options=new ArrayList<>();for(int dz=-6;dz<=6;dz++)for(int dx=-6;dx<=6;dx++)if(dx*dx+dz*dz<=36)options.add(new int[]{dx,dz});
  options.sort(Comparator.comparingDouble(o->Math.pow(x+o[0]+.5-p.x,2)+Math.pow(z+o[1]+.5-p.z,2)));
  while(true){
   StrictWaterDetour.checkDeadline(deadline);boolean pending=false;
   for(var o:options){var c=padded(x+o[0],z+o[1]);if(c==null){pending=true;continue;}
    if(c.passable()&&c.surfacePos()!=null&&c.surfacePos().getY()==y){
     if(o[0]!=0||o[1]!=0)moved++;return c.surfacePos();
    }
   }
   if(!pending)return null;StrictWaterDetour.waitTerrain(await,deadline);
  }
 }
 private List<Vec3> run(List<Vec3> path){
  // Densify only the private working line, so a long sparse edge cannot hide a coastal pinch.
  List<Vec3> samples=new ArrayList<>();samples.add(source);
  for(int i=1;i<path.size();i++){
   Vec3 a=path.get(i-1),b=path.get(i);
   if(!finite(a)||!finite(b)||Math.abs(a.y-b.y)>1e-6)return List.of();
   int steps=Math.max(1,(int)Math.ceil(Math.hypot(a.x-b.x,a.z-b.z)/12));
   if(samples.size()+(long)steps>MAX_POINTS)throw new StrictWaterDetour.Failure(WaterRouteFailureReason.NODE_BUDGET_EXCEEDED,"Shore path budget");
   for(int k=1;k<=steps;k++)samples.add(a.lerp(b,k/(double)steps));
  }
  BlockPos start=BlockPos.containing(source),goal=BlockPos.containing(destination);
  var first=StrictWaterDetour.awaitColumn(world,start,await,deadline);
  var last=StrictWaterDetour.awaitColumn(world,goal,await,deadline);
  if(!first.passable()||!last.passable()||first.surfacePos().getY()!=start.getY()||last.surfacePos().getY()!=goal.getY())return List.of();
  List<BlockPos> safe=new ArrayList<>();safe.add(start);
  for(int i=1;i<samples.size();i++){
   StrictWaterDetour.checkDeadline(deadline);BlockPos next=i==samples.size()-1?goal:nearest(samples.get(i));if(next==null)return List.of();
   BlockPos previous=safe.getLast();if(previous.equals(next))continue;
   var state=StrictWaterDetour.awaitEdge(world,previous,next,await,deadline);
   if(state==StrictWaterDetour.Check.CLEAR)safe.add(next);
   else {
    if(++repairs>64)throw new StrictWaterDetour.Failure(WaterRouteFailureReason.NODE_BUDGET_EXCEEDED,"Shore repair budget");
    var detour=StrictWaterDetour.find(world,previous,next,await,deadline,6000);
    if(detour.isEmpty())return List.of();safe.addAll(detour.subList(1,detour.size()));
   }
   if(safe.size()>MAX_POINTS)return List.of();
  }
  // No aggressive multi-leg smoothing: retain mandatory corner geometry.
  List<BlockPos> pulled=new ArrayList<>();pulled.add(safe.getFirst());
  for(int i=0;i<safe.size()-1;){int chosen=i+1;
   for(int j=Math.min(safe.size()-1,i+16);j>i+1;j--)if(StrictWaterDetour.edge(world,safe.get(i),safe.get(j))==StrictWaterDetour.Check.CLEAR){chosen=j;break;}
   pulled.add(safe.get(chosen));i=chosen;
  }
  List<Vec3> result=new ArrayList<>();result.add(source);
  for(int i=0;i<pulled.size();i++){Vec3 p=Vec3.atBottomCenterOf(pulled.get(i));if(result.getLast().distanceToSqr(p)>1e-12)result.add(p);}
  if(result.getLast().distanceToSqr(destination)>1e-12)result.add(destination);
  return List.copyOf(result);
 }
 private static boolean finite(Vec3 p){return p!=null&&Double.isFinite(p.x)&&Double.isFinite(p.y)&&Double.isFinite(p.z);}
}
