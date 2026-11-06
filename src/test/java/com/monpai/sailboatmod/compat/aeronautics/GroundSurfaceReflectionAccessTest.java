package com.monpai.sailboatmod.compat.aeronautics;

import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.BlockGetter;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class GroundSurfaceReflectionAccessTest {
    @Test void checksWholeRayChunkRectangleIncludingChunksThatAreNeitherEndpoint() {
        Set<String> checked=new HashSet<>();
        assertTrue(GroundSurfaceReflectionAccess.fullRayPathLoaded(new Vec3(15,-59,15),new Vec3(17,-63,17),
                (x,z)->{checked.add(x+","+z);return true;}));
        assertEquals(Set.of("0,0","0,1","1,0","1,1"),checked);
        assertFalse(GroundSurfaceReflectionAccess.fullRayPathLoaded(new Vec3(15,-59,15),new Vec3(17,-63,17),
                (x,z)->!(x==0&&z==1)),"loaded endpoints cannot authorize a ray across an unloaded intermediate chunk");
    }
    @Test void invalidOrUnboundedWorldRaysFailBeforeAnyChunkRead() {
        assertFalse(GroundSurfaceReflectionAccess.fullRayPathLoaded(Vec3.ZERO,new Vec3(Double.NaN,0,0),(x,z)->fail("chunk read")));
        assertFalse(GroundSurfaceReflectionAccess.fullRayPathLoaded(Vec3.ZERO,new Vec3(1e9,0,0),(x,z)->fail("chunk read")));
    }
    @Test void checksChunksVisitedByNativeTraversalPastBoundaryEndpoints() {
        Vec3[][] rays={
                {new Vec3(16,-59,0),new Vec3(17,-63,0)},
                {new Vec3(15,-59,0),new Vec3(16,-63,0)},
                {new Vec3(-16,-59,0),new Vec3(-17,-63,0)},
                {new Vec3(-15,-59,0),new Vec3(-16,-63,0)},
                {new Vec3(0,-59,16),new Vec3(0,-63,17)},
                {new Vec3(0,-59,15),new Vec3(0,-63,16)},
                {new Vec3(0,-59,-16),new Vec3(0,-63,-17)},
                {new Vec3(0,-59,-15),new Vec3(0,-63,-16)}};
        for(Vec3[] ray:rays) {
            Set<String> visited=new HashSet<>();
            BlockGetter.traverseBlocks(ray[0],ray[1],visited,(chunks,pos)->{
                chunks.add((pos.getX()>>4)+","+(pos.getZ()>>4));return null;
            },chunks->null);
            Set<String> checked=new HashSet<>();
            assertTrue(GroundSurfaceReflectionAccess.fullRayPathLoaded(ray[0],ray[1],
                    (x,z)->{checked.add(x+","+z);return true;}));
            assertTrue(checked.containsAll(visited),"native traversal visits unchecked chunks: "+visited+" vs "+checked);
            for(String missing:visited)assertFalse(GroundSurfaceReflectionAccess.fullRayPathLoaded(ray[0],ray[1],
                    (x,z)->!missing.equals(x+","+z)),"unloaded native traversal chunk "+missing);
        }
    }
    @Test void absentOrChangedOptionalRayAndWheelAbisCannotBind() {
        assertNull(GroundSurfaceReflectionAccess.bindIfAvailable(name->{throw new ClassNotFoundException(name);}));
        assertNull(GroundSurfaceReflectionAccess.bindIfAvailable(name->Object.class));
    }
}
