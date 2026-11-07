package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.route.water.StrictWaterDetour.Failure;
import com.monpai.sailboatmod.route.water.WaterRoutePostProcessor.AwaitTerrain;
import com.monpai.sailboatmod.route.water.local.BoatCollisionField;
import com.monpai.sailboatmod.route.water.local.BoatCollisionField.Cell;
import com.monpai.sailboatmod.route.water.local.BoatCollisionField.Check;
import com.monpai.sailboatmod.route.water.local.BoatCollisionField.Hull;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Refines private voyages against bounded windows of asynchronous terrain.
 * The original endpoints stay exact and every returned segment retains the hull check.
 */
public final class ShoreRouteRefiner {
   private static final int MAX_WINDOW_SAMPLES = 65_536;
   private static final int MAX_CACHE_COLUMNS = 65_536;
   private static final int MAX_PENDING_CHUNKS = 4_096;
   private static final int MAX_POINTS = 8192;
   private static final int MAX_REPAIRS = 64;
   private static final double WINDOW_DISTANCE = 256.0;
   private static final int MAX_WINDOW_STEPS = 32;
   private static final long RETRY_NANOS = 50_000_000L;
   private static final long MIN_WAIT_NANOS = 10_000_000L;
   private static final double TRANSITION = 3.0;
   private static final double EPS = 1.0E-7;
   private final RealBlockWaterMap map;
   private final AwaitTerrain await;
   private final AwaitTerrain terrainAwait;
   private final long deadline;
   private final Vec3 source;
   private final Vec3 destination;
   private final int waterY;
   private final Map<Long, WaterColumn> raw = new LinkedHashMap<>(256, 0.75F, true);
   private final Map<Long, WaterColumn> paddedCache = new LinkedHashMap<>(256, 0.75F, true);
   private final Set<Long> sampledInWindow = new HashSet<>();
   private final Map<Long, Long> pendingChunks = new HashMap<>();
   private final WaterRouteWorld cruise;
   private final BoatCollisionField field;
   private long samples;
   private long polls;
   private long sourceCalls;
   private long pendingPolls;
   private long cooldownSkips;
   private long waits;
   private long waitNanos;
   private int repairs;
   private int moved;
   private int windows;
   private int windowSamples;
   private int maxWindowSamples;
   private int windowSteps;
   private int segmentIndex = -1;
   private int verifiedPoints;
   private int maxRawCache;
   private int maxPaddedCache;
   private int maxPendingChunks;
   private double windowDistance;
   private String windowPhase = "NONE";
   private final long started = System.nanoTime();
   private String stage = "INPUT";
   private String reason = "UNFINISHED";
   private String detail = "none";
   private String observation = "none";

   private ShoreRouteRefiner(RealBlockWaterMap map, AwaitTerrain terrainAwait, long deadline, Vec3 source, Vec3 destination) {
      this.map = map;
      this.terrainAwait = terrainAwait;
      this.await = this::waitForTerrain;
      this.deadline = deadline;
      this.source = source;
      this.destination = destination;
      this.waterY = (int)Math.round(source.y);
      this.cruise = new WaterRouteWorld() {
         @Override
         public WaterColumn sample(int x, int z, WaterRoutePolicy policy) {
            return ShoreRouteRefiner.this.padded(x, z);
         }

         @Override
         public boolean canLoadMoreChunks(int requested) {
            return false;
         }

         @Override
         public int consumedChunkLoads() {
            return 0;
         }
      };
      Hull hull = new Hull(1.0, 1.0, (double)this.waterY - 0.22, (double)(this.waterY + 2), this.waterY - 1);
      this.field = new BoatCollisionField(hull, (x, z) -> {
         WaterColumn column = this.one(x, z);
         if (column == null) {
            return Cell.unknown();
         } else {
            boolean passable = column.passable() && column.surfacePos() != null && column.surfacePos().getY() == this.waterY;
            return new Cell(true, passable, List.of());
         }
      }, List.of());
   }

   public static List<Vec3> refine(List<Vec3> path, RealBlockWaterMap map, AwaitTerrain await, long deadline) {
      if (path == null || path.size() < 2 || path.size() > 65536 || map == null
            || !finite(path.getFirst()) || !finite(path.getLast())) {
         System.getLogger("Sailboat ShoreClearance").log(Level.WARNING, "[navfix5-shore] output=0 stage=INPUT reason=INVALID_INPUT");
         return List.of();
      }
      var refiner = new ShoreRouteRefiner(map, await, deadline, path.getFirst(), path.getLast());
      List<Vec3> result = List.of();
      try {
         result = refiner.run(path);
         return result;
      } catch (CancellationException cancelled) {
         refiner.reason = "CANCELLED";
         refiner.detail = refiner.observation;
         throw cancelled;
      } catch (Failure failure) {
         WaterRouteFailureReason reason = failure.reason();
         if (reason == WaterRouteFailureReason.TIMEOUT && !refiner.pendingChunks.isEmpty()) {
            reason = WaterRouteFailureReason.TERRAIN_NOT_READY;
         }
         refiner.reason = reason.name();
         refiner.detail = failure.getMessage() + "; " + refiner.observation;
         if (reason != failure.reason()) throw new Failure(reason, refiner.detail);
         throw failure;
      } finally {
         System.getLogger("Sailboat ShoreClearance")
            .log(
               Level.INFO,
               "[navfix5-shore] input="
                  + path.size()
                  + " output="
                  + result.size()
                  + " stage="
                  + refiner.stage
                  + " reason="
                  + refiner.reason
                  + " detail="
                  + refiner.detail
                  + " start="
                  + refiner.source
                  + " end="
                  + refiner.destination
                  + " waterY="
                  + refiner.waterY
                  + " moved="
                  + refiner.moved
                  + " repairedEdges="
                  + refiner.repairs
                  + " samples="
                  + refiner.samples
                  + " sourceCalls="
                  + refiner.sourceCalls
                  + " polls="
                  + refiner.polls
                  + " pendingPolls="
                  + refiner.pendingPolls
                  + " cooldownSkips="
                  + refiner.cooldownSkips
                  + " waits="
                  + refiner.waits
                  + " waitMs="
                  + refiner.waitNanos / 1000000L
                  + " window="
                  + refiner.windows
                  + " windowSamples="
                  + refiner.windowSamples
                  + " maxWindowSamples="
                  + refiner.maxWindowSamples
                  + " segment="
                  + refiner.segmentIndex
                  + " verifiedPoints="
                  + refiner.verifiedPoints
                  + " pendingChunks="
                  + refiner.pendingChunks.size()
                  + " maxRawCache="
                  + refiner.maxRawCache
                  + " maxPaddedCache="
                  + refiner.maxPaddedCache
                  + " maxPendingChunks="
                  + refiner.maxPendingChunks
                  + " elapsedMs="
                  + (System.nanoTime() - refiner.started) / 1000000L
                  + " cruiseCentreMargin>=2; exactEndpointTransition<=3"
            );
      }
   }

   private static long key(int x, int z) {
      return ((long) x << 32) ^ (z & 0xffffffffL);
   }

   private WaterColumn one(int x, int z) {
      StrictWaterDetour.checkDeadline(this.deadline);
      this.polls++;
      long columnKey = key(x, z);
      WaterColumn column = this.raw.get(columnKey);
      if (column == null) {
         long chunkKey = key(x >> 4, z >> 4);
         long now = System.nanoTime();
         Long retryAt = this.pendingChunks.get(chunkKey);
         if (retryAt != null && now - retryAt < 0L) {
            this.pendingPolls++;
            this.cooldownSkips++;
            this.observation = "unknown@(" + x + "," + z + ") chunk=(" + (x >> 4) + "," + (z >> 4) + ")";
            return null;
         }

         if (this.sampledInWindow.add(columnKey)) {
            this.samples++;
            this.windowSamples++;
            this.maxWindowSamples = Math.max(this.maxWindowSamples, this.windowSamples);
            if (this.windowSamples > MAX_WINDOW_SAMPLES) {
               throw new Failure(
                  WaterRouteFailureReason.NODE_BUDGET_EXCEEDED, "Shore unique-column window budget; window=" + this.windows + ",segment=" + this.segmentIndex
               );
            }
         }

         this.sourceCalls++;
         column = this.map.sampleHullStrict(x, z, 0);
         if (column == null) {
            if (!this.pendingChunks.containsKey(chunkKey) && this.pendingChunks.size() >= MAX_PENDING_CHUNKS) {
               throw new Failure(WaterRouteFailureReason.CHUNK_BUDGET_EXCEEDED, "Shore pending chunk budget");
            }

            this.pendingChunks.put(chunkKey, System.nanoTime() + RETRY_NANOS);
            this.maxPendingChunks = Math.max(this.maxPendingChunks, this.pendingChunks.size());
            this.pendingPolls++;
         } else {
            this.pendingChunks.remove(chunkKey);
            this.cache(this.raw, columnKey, column);
            this.maxRawCache = Math.max(this.maxRawCache, this.raw.size());
         }
      }

      if (column == null) {
         this.observation = "unknown@(" + x + "," + z + ") chunk=(" + (x >> 4) + "," + (z >> 4) + ")";
      } else if (!column.passable() || column.surfacePos() == null) {
         this.observation = "dry_or_occupied@(" + x + "," + z + ")";
      } else if (column.surfacePos().getY() != this.waterY) {
         this.observation = "water_height@(" + x + "," + z + ")=" + column.surfacePos().getY() + ",expected=" + this.waterY;
      }

      return column;
   }

   private void cache(Map<Long, WaterColumn> cache, long columnKey, WaterColumn column) {
      cache.put(columnKey, column);
      if (cache.size() > MAX_CACHE_COLUMNS) {
         Iterator<Long> eldest = cache.keySet().iterator();
         eldest.next();
         eldest.remove();
      }
   }

   private void budgetWindow(String phase, int segment, double distance) {
      this.segmentIndex = segment;
      if (!phase.equals(this.windowPhase) || this.windowDistance + distance > WINDOW_DISTANCE || this.windowSteps >= MAX_WINDOW_STEPS) {
         this.windowPhase = phase;
         this.windows++;
         this.windowSteps = 0;
         this.windowDistance = 0.0;
         this.windowSamples = 0;
         this.sampledInWindow.clear();
         long now = System.nanoTime();
         this.pendingChunks.entrySet().removeIf(pending -> now - pending.getValue() >= 0L);
      }

      this.windowSteps++;
      this.windowDistance += Math.max(0.0, distance);
   }

   private void waitForTerrain(long requestedMillis) {
      StrictWaterDetour.checkDeadline(this.deadline);
      long started = System.nanoTime();
      long availableNanos = this.deadline - started;
      long targetWaitNanos = Math.min(MIN_WAIT_NANOS, Math.max(0L, availableNanos));
      this.waits++;

      try {
         if (this.terrainAwait != null) {
            this.terrainAwait.await(Math.min(10L, Math.max(1L, requestedMillis)));
         }

         for (long remainingNanos = targetWaitNanos - (System.nanoTime() - started); remainingNanos > 0L; remainingNanos = targetWaitNanos - (System.nanoTime() - started)) {
            WaterRouteCancellation.check();
            LockSupport.parkNanos(Math.min(remainingNanos, MIN_WAIT_NANOS));
            WaterRouteCancellation.check();
         }
      } finally {
         this.waitNanos += System.nanoTime() - started;
      }

      StrictWaterDetour.checkDeadline(this.deadline);
   }

   private WaterColumn padded(int x, int z) {
      StrictWaterDetour.checkDeadline(this.deadline);
      long columnKey = key(x, z);
      WaterColumn cached = this.paddedCache.get(columnKey);
      if (cached != null) return cached;
      boolean pending = false;
      for (int radius = 0; radius <= 2; radius++) {
         for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
               if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
               WaterColumn column = this.one(x + dx, z + dz);
               if (column == null) {
                  pending = true;
                  continue;
               }
               if (!column.passable() || column.surfacePos() == null || column.surfacePos().getY() != this.waterY) {
                  WaterColumn blocked = WaterColumn.blocked();
                  this.cache(this.paddedCache, columnKey, blocked);
                  this.maxPaddedCache = Math.max(this.maxPaddedCache, this.paddedCache.size());
                  return blocked;
               }
            }
         }
      }
      if (pending) return null;
      WaterColumn clear = WaterColumn.passable(new BlockPos(x, this.waterY, z), 0.0);
      this.cache(this.paddedCache, columnKey, clear);
      this.maxPaddedCache = Math.max(this.maxPaddedCache, this.paddedCache.size());
      return clear;
   }

   private BoatCollisionField.Check segment(Vec3 a,Vec3 b,boolean arrival) {
       // Arrival never bypasses the physical hull, and cannot relax a long or middle edge.
       return field.navfix3$segment(a,b,arrival?0:2.0);
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
           if((long)dense.size()+(long)steps>MAX_POINTS)throw new StrictWaterDetour.Failure(
                   WaterRouteFailureReason.NODE_BUDGET_EXCEEDED,"Shore input budget");
           for(int k=1;k<=steps;k++)dense.add(a.lerp(b,k/(double)steps));
       }
       budgetWindow("ENDPOINTS", 0, 0.0);
       if(!physicalEndpoint(source,"START")||!physicalEndpoint(destination,"END"))return List.of();
       BlockPos start=anchor(source,false);if(start==null)return List.of();
       BlockPos end=anchor(destination,true);if(end==null)return List.of();
       // Remove only contiguous endpoint neighbourhoods, never arbitrary mid-voyage points.
       int first=1,last=dense.size()-2;
       while(first<=last&&horizontal(dense.get(first),source)<=TRANSITION)first++;
       while(last>=first&&horizontal(dense.get(last),destination)<=TRANSITION)last--;
       stage="CRUISE";List<BlockPos> safe=new ArrayList<>();safe.add(start);
       verifiedPoints = 1;
       Vec3 previous = Vec3.atBottomCenterOf(start);
       for(int i=first;i<=last;i++) {
           budgetWindow("CRUISE", i, horizontal(previous, dense.get(i)));
           BlockPos next=nearest(dense.get(i));if(next==null||!append(safe,next))return List.of();
           verifiedPoints = safe.size();
           previous = dense.get(i);
       }
       budgetWindow("CRUISE", last + 1, horizontal(previous, Vec3.atBottomCenterOf(end)));
       if(!append(safe,end))return List.of();
       verifiedPoints = safe.size();
       List<BlockPos> pulled=new ArrayList<>();pulled.add(start);
       stage = "SMOOTH";
       for(int i=0;i<safe.size()-1;) {
           budgetWindow("SMOOTH", i, i == 0 ? 0.0 : horizontal(
                   Vec3.atBottomCenterOf(safe.get(i - 1)), Vec3.atBottomCenterOf(safe.get(i))));
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
               budgetWindow("FINAL_SWEEP", i, horizontal(a, b) / pieces);
               if(awaitSegment(a.lerp(b,k/(double)pieces),a.lerp(b,(k+1)/(double)pieces),arrival)
                       !=BoatCollisionField.Check.CLEAR) {
                   reason="EXACT_SEGMENT_BLOCKED";detail="edge="+i+","+observation;return List.of();
               }
           }
       }
       stage="COMPLETE";reason="OK";detail="none";return List.copyOf(result);
   }

   private static void add(List<Vec3> path, Vec3 point) {
      if (path.isEmpty() || ((Vec3)path.getLast()).distanceToSqr(point) > 9.999999999999998E-15) {
         path.add(point);
      }
   }

   private static double horizontal(Vec3 from, Vec3 to) {
      return Math.hypot(from.x - to.x, from.z - to.z);
   }

   private static boolean finite(Vec3 point) {
      return point != null && Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
   }
}
