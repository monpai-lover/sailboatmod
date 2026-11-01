package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WaterRescuePlannerTest {
    static Vec3 p(double x,double z){return new Vec3(x+.5,65,z+.5);}
    static RealBlockWaterMap map(boolean sealed) {
        RealBlockWaterMap map=new RealBlockWaterMap(null,63).retryUnknownPrediction();
        for(int cx=-8;cx<=40;cx++)for(int cz=-8;cz<=8;cz++) {
            boolean[] w=new boolean[256];int[] y=new int[256];Arrays.fill(y,64);
            for(int i=0;i<256;i++) {
                int x=cx*16+(i&15),z=cz*16+(i>>4);
                w[i]=x>=0&&x<600&&z>=-64&&z<64&&!(x>=42&&x<=45&&(sealed||Math.abs(z)<=12));
            }
            map.seedChunk(cx,cz,new NbtChunkWaterReader.ChunkColumns(w,y));
        }
        return map;
    }
    @Test void sparseVoyageRescuesLocallyWithoutSearchingToDistantDock() {
        var route=List.of(p(0,0),p(20000,0));
        var result=WaterRescuePlanner.plan(map(false),p(10,0),route,1,null);
        assertTrue(result.successful(),result.reason().toString());
        assertEquals(1,result.value().tailIndex());
        assertTrue(result.value().detour().getLast().x<600);
        assertTrue(result.value().detour().stream().anyMatch(p->Math.abs(p.z)>12),"detour goes around actual wall");
        var world=StrictWaterDetour.world(map(false),1);
        for(int i=1;i<result.value().detour().size();i++) {
            var a=result.value().detour().get(i-1);var b=result.value().detour().get(i);
            assertEquals(StrictWaterDetour.Check.CLEAR,StrictWaterDetour.edge(world,cell(a),cell(b)));
        }
    }
    @Test void startHullOnLandIsNotTeleportedThroughWallToNearbyWater() {
        assertFalse(WaterRescuePlanner.plan(map(false),p(43,0),List.of(p(0,0),p(500,0)),1,null).successful());
    }
    @Test void sealedWallNeverReturnsFakeSuccessfulSplice() {
        assertFalse(WaterRescuePlanner.plan(map(true),p(30,0),List.of(p(0,0),p(500,0)),1,null).successful());
    }
    @Test void rejoinTargetsAreForwardAndDoNotRequireRevisitingPassedCorner() {
        var route=List.of(p(0,0),p(10,0),p(10,500));
        var targets=WaterRescuePlanner.targets(p(10,40),route,2);
        assertFalse(targets.isEmpty());
        assertTrue(targets.stream().allMatch(t->t.point().z>40));
    }
    @Test void inheritedCancellationCannotBeHiddenByNestedFalseToken() {
        assertThrows(java.util.concurrent.CancellationException.class,()->WaterRouteCancellation.run(()->true,()->
                WaterRouteCancellation.run(()->false,()->WaterRescuePlanner.plan(map(false),p(10,0),List.of(p(0,0),p(500,0)),1,null))));
    }
    @Test void actualWaterHeightOverridesDefaultSeaLevelButNotDestinationHeight() {
        var result=WaterRescuePlanner.plan(map(false),new Vec3(10.5,64.42,30.5),List.of(p(0,30),p(500,30)),1,null);
        assertTrue(result.successful());assertTrue(result.value().detour().stream().allMatch(p->p.y==65));
        assertFalse(WaterRescuePlanner.plan(map(false),p(10,30),List.of(p(0,30).add(0,1,0),p(500,30).add(0,1,0)),1,null).successful());
    }
    static BlockPos cell(Vec3 p){return new BlockPos((int)Math.floor(p.x),(int)Math.floor(p.y),(int)Math.floor(p.z));}
}
