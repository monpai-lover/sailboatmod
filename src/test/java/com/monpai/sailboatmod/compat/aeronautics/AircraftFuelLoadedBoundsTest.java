package com.monpai.sailboatmod.compat.aeronautics;
import java.util.Set;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AircraftFuelLoadedBoundsTest {
    private long chunk(int x,int z){return ChunkPos.asLong(x,z);}
    @Test void inclusivePositiveMaxRequiresTheLastOccupiedChunk(){
        assertEquals("fuel_body_not_loaded",AircraftFuelReflectionAccess.loadedBoundsProblem(0,16,0,15,Set.of(chunk(0,0))));
        assertEquals("",AircraftFuelReflectionAccess.loadedBoundsProblem(0,16,0,15,Set.of(chunk(0,0),chunk(1,0))));
        assertEquals("fuel_body_not_loaded",AircraftFuelReflectionAccess.loadedBoundsProblem(0,15,0,16,Set.of(chunk(0,0))));
    }
    @Test void aOneCellOrOneColumnHullIsValidOnlyWhenItsChunkIsLoaded(){
        assertEquals("",AircraftFuelReflectionAccess.loadedBoundsProblem(7,7,8,8,Set.of(chunk(0,0))));
        assertEquals("fuel_body_not_loaded",AircraftFuelReflectionAccess.loadedBoundsProblem(7,7,8,8,Set.of()));
        assertEquals("",AircraftFuelReflectionAccess.loadedBoundsProblem(16,16,-16,0,Set.of(chunk(1,-1),chunk(1,0))));
    }
    @Test void zeroAndNegativeEdgesUseFloorChunkCoordinates(){
        assertEquals("fuel_body_not_loaded",AircraftFuelReflectionAccess.loadedBoundsProblem(-16,0,-16,-1,Set.of(chunk(-1,-1))));
        assertEquals("",AircraftFuelReflectionAccess.loadedBoundsProblem(-16,0,-16,-1,Set.of(chunk(-1,-1),chunk(0,-1))));
        assertEquals("",AircraftFuelReflectionAccess.loadedBoundsProblem(-16,-1,-16,-1,Set.of(chunk(-1,-1))));
    }
    @Test void malformedOrExcessiveBoundsStillFailClosed(){
        assertEquals("fuel_observation_limit",AircraftFuelReflectionAccess.loadedBoundsProblem(1,0,0,0,Set.of()));
        assertEquals("fuel_observation_limit",AircraftFuelReflectionAccess.loadedBoundsProblem(0,16*33,0,0,Set.of()));
        assertEquals("fuel_observation_limit",AircraftFuelReflectionAccess.loadedBoundsProblem(Integer.MIN_VALUE,Integer.MAX_VALUE,0,0,Set.of()));
    }
    @Test void extremeSingleCellsDoNotRequireSubtractingOrAddingToMaximum(){
        assertEquals("",AircraftFuelReflectionAccess.loadedBoundsProblem(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,
                Set.of(chunk(Integer.MIN_VALUE>>4,Integer.MAX_VALUE>>4))));
    }
}
