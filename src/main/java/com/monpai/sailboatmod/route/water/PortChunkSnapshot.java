package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.util.OfflineChunkPalette;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.concurrent.CompletableFuture;

/** Copies at most 32 loaded columns per mailbox step, never touches an unloaded chunk. */
public final class PortChunkSnapshot {
    private PortChunkSnapshot() {}
    public record Capture(boolean loaded, NbtChunkWaterReader.ChunkColumns columns) {}
    public static CompletableFuture<Capture> capture(ServerLevel level,int cx,int cz) {
        CompletableFuture<Capture> out=new CompletableFuture<>();
        class Cursor {
            final boolean[] water=new boolean[256];final int[] heights=new int[256];
            int index;LevelChunk original;
            void enqueue(){
                if(out.isDone())return;
                PortAsyncRuntime.onMain(level.getServer(),()->{
                    if(out.isDone())return null;
                    LevelChunk chunk=level.getChunkSource().getChunkNow(cx,cz);
                    if(chunk==null || (original!=null && chunk!=original)){
                        out.complete(new Capture(original!=null,null));return null;
                    }
                    original=chunk;
                    BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();
                    int end=Math.min(256,index+32);
                    for(;index<end;index++){
                        int x=index&15,z=index>>4;heights[index]=-999;
                        for(int y=chunk.getHeight(Heightmap.Types.WORLD_SURFACE,x,z);y>=chunk.getMinBuildHeight();y--){
                            var state=chunk.getBlockState(pos.set((cx<<4)+x,y,(cz<<4)+z));
                            if(state.isAir()||OfflineChunkPalette.isDefaultSurfaceNoise(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()))continue;
                            heights[index]=y;water[index]=WaterSurfaceRules.isNavigableSurface(state);break;
                        }
                    }
                    if(index==256)out.complete(new Capture(true,new NbtChunkWaterReader.ChunkColumns(water,heights)));
                    return null;
                }).whenComplete((v,e)->{if(e!=null)out.completeExceptionally(e);else if(!out.isDone())enqueue();});
            }
        }
        new Cursor().enqueue();return out;
    }
}
