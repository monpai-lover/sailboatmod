package com.monpai.sailboatmod.transport.physical;

import static org.junit.jupiter.api.Assertions.*;
import com.monpai.sailboatmod.transport.air.airport.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class AirportLoadingFootprintTest {
    @SuppressWarnings("unchecked") private static Optional<Set<ChunkPos>> collect(List<AirportFacility> a,AirportGroundLayout ga,List<AirportFacility> b,AirportGroundLayout gb)throws Exception {
        return (Optional<Set<ChunkPos>>)Class.forName("com.monpai.sailboatmod.transport.physical.AirportLoadingFootprint")
                .getMethod("collect",List.class,AirportGroundLayout.class,List.class,AirportGroundLayout.class).invoke(null,a,ga,b,gb);
    }
    private static AirportFacility runway(String id,int x,int z,int ex,int ez){return new AirportFacility(id,AirportFacility.Type.RUNWAY,new BlockPos(x,81,z),new BlockPos(ex,81,ez),28,12);}
    @Test void twoLongRunwaysIncludeFarEndsWithoutLoadingTheFlightBetweenThem()throws Exception {
        var a=runway("a",0,0,2048,0);var b=runway("b",10000,10000,12048,10000);
        var chunks=collect(List.of(a),AirportGroundLayout.empty(),List.of(b),AirportGroundLayout.empty()).orElseThrow();
        assertTrue(chunks.contains(new ChunkPos(a.end())));assertTrue(chunks.contains(new ChunkPos(b.end())));
        assertFalse(chunks.contains(new ChunkPos(5000>>4,5000>>4)));assertTrue(chunks.size()<=4096);
    }
    @Test void diagonalRunwayDoesNotRequestItsEmptyBoundingBoxCorners()throws Exception {
        var r=runway("a",0,0,1448,1448);var chunks=collect(List.of(r),AirportGroundLayout.empty(),List.of(),AirportGroundLayout.empty()).orElseThrow();
        assertFalse(chunks.contains(new ChunkPos(0,1448>>4)));assertTrue(chunks.contains(new ChunkPos(r.end())));
    }
    @Test void groundFootprintMatchesInclusiveLoadedBoundsAtChunkEdges()throws Exception {
        var s=new AirportParkingStand("s",new Vec3(0,81,0),0,32,32,8,true);
        var g=new AirportGroundLayout(1,List.of(s),List.of());var chunks=collect(List.of(),g,List.of(),AirportGroundLayout.empty()).orElseThrow();
        assertTrue(chunks.contains(new ChunkPos(1,1)),"AirportBE loadedBounds includes its exact max boundary");
        assertTrue(chunks.contains(new ChunkPos(-1,-1)));
    }
    @Test void hugeGroundMetadataIsRejectedAtTheLeaseCap()throws Exception {
        List<AirportParkingStand> stands=new ArrayList<>();for(int i=0;i<32;i++)stands.add(new AirportParkingStand("s"+i,new Vec3(i*1000,81,0),0,64,64,8,true));
        // Two diagonal corridor AABBs alone exceed the dedicated loading budget.
        var route=new AirportTaxiRoute("r",AirportTaxiRoute.Direction.INBOUND,"a","s0",line(),64,8,1,-1);
        var g=new AirportGroundLayout(1,stands,List.of(route));
        assertTrue(collect(List.of(runway("a",0,0,1448,1448)),g,List.of(),AirportGroundLayout.empty()).isEmpty());
    }
    private static List<Vec3> line(){List<Vec3> p=new ArrayList<>();for(int i=0;i<=12;i++)p.add(new Vec3(i*170,81,i*170));return p;}
}
