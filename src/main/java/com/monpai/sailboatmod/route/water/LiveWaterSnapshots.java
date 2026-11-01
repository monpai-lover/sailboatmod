package com.monpai.sailboatmod.route.water;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/** Worker waits for budgeted main-thread copies; never reads mutable chunks off-thread.
 * Loaded data overrides NBT. A failed live capture is UNKNOWN, not permission to use stale disk data.
 */
final class LiveWaterSnapshots {
    private record Pending(int x,int z,java.util.concurrent.CompletableFuture<PortChunkSnapshot.Capture> future) { }
    private LiveWaterSnapshots() { }
    static void seedNearby(ServerLevel level,RealBlockWaterMap map,Vec3 position,long deadline) {
        if(level==null)return;
        if(level.getServer().isSameThread())throw new IllegalStateException("Snapshot wait on tick thread");
        int cx=(int)Math.floor(position.x)>>4,cz=(int)Math.floor(position.z)>>4;
        var pending=new ArrayList<Pending>();
        for(int z=cz-1;z<=cz+1;z++)for(int x=cx-1;x<=cx+1;x++)pending.add(new Pending(x,z,PortChunkSnapshot.capture(level,x,z)));
        try {
            for(var p:pending) {
                WaterRouteCancellation.check();long left=deadline-System.nanoTime();
                if(left<=0)throw new java.util.concurrent.TimeoutException();
                var copy=p.future.get(left,TimeUnit.NANOSECONDS);
                if(copy.loaded()&&copy.columns()==null)throw new java.util.concurrent.TimeoutException("Live chunk changed during capture");
                if(copy.loaded())map.seedChunk(p.x,p.z,copy.columns());
            }
        } catch(InterruptedException interrupted){Thread.currentThread().interrupt();WaterRouteCancellation.check();
            throw new StrictWaterDetour.Failure(WaterRouteFailureReason.CANCELLED,"Live snapshot cancelled");
        } catch(java.util.concurrent.ExecutionException|java.util.concurrent.TimeoutException unavailable){
            throw new StrictWaterDetour.Failure(WaterRouteFailureReason.TERRAIN_NOT_READY,"Live water snapshot not ready (no stale-disk fallback)");
        } finally {for(var p:pending)if(!p.future.isDone())p.future.cancel(false);}
    }
}
