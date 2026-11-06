package com.monpai.sailboatmod.transport.air.airport;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

class AirportFacilityChunkBudgetTest {
    @Test void longestRotatedRunwayMetadataFootprintContainsEveryRequiredChunkWithinBudget() {
        for (int width : new int[]{32,64}) for (BlockPos end : List.of(new BlockPos(2048,80,0),new BlockPos(1448,80,1448),new BlockPos(1024,80,1773))) {
            var runway=new AirportFacility("r",AirportFacility.Type.RUNWAY,new BlockPos(0,80,0),end,width,12);
            var footprint=runway.clearanceChunks().orElseThrow();
            assertTrue(footprint.size()<=AirportFacility.MAX_CLEARANCE_CHUNKS);
            var expected=new HashSet<Long>();var bounds=runway.clearanceBounds();
            for(int x=(int)Math.floor(bounds.minX);x<(int)Math.ceil(bounds.maxX);x++)
                for(int z=(int)Math.floor(bounds.minZ);z<(int)Math.ceil(bounds.maxZ);z++)
                    if(runway.clearanceBaseY(x,z)!=Integer.MIN_VALUE||runway.requiresSupport(x,z))expected.add(ChunkPos.asLong(x>>4,z>>4));
            assertTrue(footprint.containsAll(expected),"must not omit an approach, support or edge chunk: "+end);
            assertTrue(runway.clearanceChunks(1).isEmpty(),"metadata work exceeding its explicit budget must reject");
        }
    }
}
