package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import java.util.*;

/** Destination-level single-flight jobs for automatic market dispatch. Cargo and payment
 * remain in the existing transactional dispatcher until a real route is available.
 */
@EventBusSubscriber(modid=SailboatMod.MODID)
public final class WaterDestinationService {
    private record Key(String dimension,BlockPos from,BlockPos to) {}
    private static final class Job {
        final ServerLevel level; final Key key; final UUID ticket=UUID.randomUUID(); final long started=System.nanoTime();
        WaterRouteTask task; boolean finished,released; long retryAfter;
        Job(ServerLevel level,Key key){this.level=level;this.key=key;}
        void release(){
            if(released)return;released=true;
            level.getChunkSource().removeRegionTicket(TICKET,new net.minecraft.world.level.ChunkPos(key.from),0,ticket);
            level.getChunkSource().removeRegionTicket(TICKET,new net.minecraft.world.level.ChunkPos(key.to),0,ticket);
        }
    }
    private static final Map<MinecraftServer,Map<Key,Job>> JOBS=new WeakHashMap<>();
    private static final net.minecraft.server.level.TicketType<UUID> TICKET=
            net.minecraft.server.level.TicketType.create("sailboatmod_destination",UUID::compareTo);
    private WaterDestinationService(){}
    /** Returns an existing exact destination index, or -1 while a bounded background job prepares it. */
    public static int ensureMarketRoute(ServerLevel level,DockBlockEntity source,DockBlockEntity target) {
        if(!PortDockSnapshot.isPort(source)||!PortDockSnapshot.isPort(target)
                ||source==target||!WaterRouteCandidateService.canListCandidate(level,source,target).successful())return -1;
        int existing=source.findRouteIndexByDestinationDock(target.getBlockPos(),target.getDockName());
        if(existing>=0)return existing;
        Map<Key,Job> jobs=JOBS.computeIfAbsent(level.getServer(),s->new LinkedHashMap<>());
        Key key=new Key(level.dimension().location().toString(),source.getBlockPos().immutable(),target.getBlockPos().immutable());
        Job old=jobs.get(key);long now=System.nanoTime();
        if(old!=null&&(!old.finished||now<old.retryAfter))return -1;
        if(jobs.values().stream().filter(j->!j.finished).count()>=8)return -1;
        jobs.entrySet().removeIf(e->e.getValue().finished&&now>=e.getValue().retryAfter);
        if(jobs.size()>=64)return -1;
        Job job=new Job(level,key);jobs.put(key,job);
        level.getChunkSource().addRegionTicket(TICKET,new net.minecraft.world.level.ChunkPos(key.from),0,job.ticket);
        level.getChunkSource().addRegionTicket(TICKET,new net.minecraft.world.level.ChunkPos(key.to),0,job.ticket);
        try {
        var submitted=WaterAutoRouteService.submitDestinationRoute(level,source,target,null,result->{
            job.finished=true;job.retryAfter=System.nanoTime()+60_000_000_000L;job.release();
        });
        if(!submitted.successful()){job.finished=true;job.retryAfter=now+10_000_000_000L;job.release();}
        else job.task=WaterRouteTaskService.global().find(key.dimension+"|"+key.from.asLong()+"|"+key.to.asLong());
        } catch(RuntimeException failure) {
            job.finished=true;job.retryAfter=now+60_000_000_000L;job.release();
            com.mojang.logging.LogUtils.getLogger().warn("[WaterPath] 自动目的地任务准备失败 {}",key,failure);
        }
        return -1;
    }
    public static boolean pending(ServerLevel level,BlockPos from,BlockPos to){
        var jobs=JOBS.get(level.getServer());if(jobs==null)return false;
        Job j=jobs.get(new Key(level.dimension().location().toString(),from,to));return j!=null&&!j.finished;
    }
    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){
        var jobs=JOBS.get(event.getServer());if(jobs==null)return;
        long now=System.nanoTime();
        for(Job j:jobs.values())if(!j.finished&&now-j.started>600_000_000_000L){
            if(j.task!=null)j.task.cancel();j.finished=true;j.retryAfter=now+60_000_000_000L;j.release();
        }
    }
    @SubscribeEvent public static void stop(net.neoforged.neoforge.event.server.ServerStoppingEvent event){
        var jobs=JOBS.remove(event.getServer());if(jobs==null)return;
        for(Job j:jobs.values()){if(j.task!=null&&!j.finished)j.task.cancel();j.release();}
    }
}
