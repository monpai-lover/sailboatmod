package com.monpai.sailboatmod.transport.physical;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class GroundSurfaceObservationTest {
    private static final UUID BODY = new UUID(7, 50);
    private static final long NOW = 1_000_000_000L;

    private GroundSurfaceObservation.RayHit ray(int index, double y) {
        return new GroundSurfaceObservation.RayHit(index, new Vec3(index,-59.2,0), new Vec3(index,-64.2,0),
                new Vec3(index,y,0), new BlockPos(index,-61,0), .8, true,true,true,true);
    }
    private GroundSurfaceObservation.WheelEvidence wheel(int x, int z) {
        return new GroundSurfaceObservation.WheelEvidence(new BlockPos(x,0,z),BODY,.75,.65-(.8+.10833333333333334-.75),.8,
                List.of(ray(-1,-60),ray(0,-60),ray(1,-60)));
    }
    private List<GroundSurfaceObservation.WheelEvidence> wheels() {
        return List.of(wheel(0,0),wheel(2,0),wheel(0,2),wheel(2,2));
    }
    private GroundSurfaceObservation observe(List<GroundSurfaceObservation.WheelEvidence> wheels) {
        return GroundSurfaceObservation.fromEvidence(BODY,42,NOW,4,wheels);
    }
    private GroundSurfaceObservation.WheelEvidence rays(GroundSurfaceObservation.WheelEvidence wheel,
            List<GroundSurfaceObservation.RayHit> rays) {
        return new GroundSurfaceObservation.WheelEvidence(wheel.wheelPosition(),wheel.physicalId(),wheel.tireRadius(),
                wheel.suspensionCompression(),wheel.nativeExtension(),rays);
    }
    private List<GroundSurfaceObservation.WheelEvidence> replace(GroundSurfaceObservation.WheelEvidence wheel) {
        var values=new ArrayList<>(wheels()); values.set(0,wheel); return values;
    }

    @Test void independentStaticUpHitsRecoverActualSurfaceWithoutHullOrAxleSubstitution() {
        var proof=observe(wheels());
        assertTrue(proof.available(),proof.reason());
        assertEquals(-60,proof.planeY(),1e-9);
        assertEquals(2.6512641907,-57.3487358093-proof.planeY(),1e-9);
        assertNotEquals(-57.3487358093-(-59.2979259434),-57.3487358093-proof.planeY(),.7);
        assertEquals(4,proof.wheels().size());
    }
    @Test void everyRegisteredWheelMustBePresentUniqueSameBodyAndPositivelySupported() {
        assertFalse(observe(wheels().subList(0,3)).available());
        var first=wheel(0,0);
        assertFalse(observe(List.of(first,first,wheel(0,2),wheel(2,2))).available());
        assertFalse(observe(replace(new GroundSurfaceObservation.WheelEvidence(first.wheelPosition(),UUID.randomUUID(),
                first.tireRadius(),first.suspensionCompression(),first.nativeExtension(),first.rays()))).available());
        assertFalse(observe(replace(new GroundSurfaceObservation.WheelEvidence(first.wheelPosition(),BODY,.75,0,.8,first.rays()))).available());
    }
    @Test void anyMissWallOtherBodyUnloadedPathOrNonfiniteHitRejectsWholeProof() {
        for(int failure=0;failure<5;failure++) {
            var first=wheel(0,0); var hits=new ArrayList<>(first.rays()); var hit=hits.get(0);
            hits.set(0,new GroundSurfaceObservation.RayHit(-1,hit.worldStart(),hit.worldEnd(),
                    failure==4?new Vec3(0,Double.NaN,0):hit.worldHit(),hit.terrainBlock(),.8,
                    failure!=0,failure!=1,failure!=2,failure!=3));
            assertFalse(observe(replace(rays(first,hits))).available(),"failure="+failure);
        }
    }
    @Test void laterRayMetadataCannotHideNearestRayExtensionMismatchOrDifferentPlane() {
        var first=wheel(0,0); var hits=new ArrayList<>(first.rays()); var hit=hits.get(0);
        hits.set(0,new GroundSurfaceObservation.RayHit(-1,hit.worldStart(),hit.worldEnd(),hit.worldHit(),hit.terrainBlock(),.4,true,true,true,true));
        assertFalse(observe(replace(rays(first,hits))).available());
        hits=new ArrayList<>(first.rays());hits.set(0,ray(-1,-59.5));
        assertFalse(observe(replace(rays(first,hits))).available());
        assertFalse(observe(replace(rays(first,List.of(ray(-1,-60),ray(-1,-60),ray(1,-60))))).available());
    }
    @Test void proofIsImmutableAndOnlyFreshWithinCurrentServerTickAndBoundedWallAge() {
        var source=new ArrayList<>(wheels());var proof=observe(source);source.clear();
        assertEquals(4,proof.wheels().size());
        assertThrows(UnsupportedOperationException.class,()->proof.wheels().clear());
        assertTrue(proof.freshAt(42,NOW+10_000_000L));
        assertFalse(proof.freshAt(43,NOW));
        assertFalse(proof.freshAt(42,NOW-1));
        assertFalse(proof.freshAt(42,NOW+250_000_001L));
    }
}
