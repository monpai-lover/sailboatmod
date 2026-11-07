package com.monpai.sailboatmod.transport.physical;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.HashSet;
import java.util.UUID;

/** Ephemeral, read-only proof of the actual static surface under every registered wheel. */
public record GroundSurfaceObservation(UUID physicalId, double planeY, long observedGameTick, long observedNanos,
        int expectedWheelCount, List<WheelEvidence> wheels, boolean available, String reason) {
    public GroundSurfaceObservation { wheels=List.copyOf(wheels); }
    public record RayHit(int rayIndex, Vec3 worldStart, Vec3 worldEnd, Vec3 worldHit, BlockPos terrainBlock,
            double localExtension, boolean hit, boolean staticTerrain, boolean upwardFace, boolean wholePathLoaded) {
        public RayHit { terrainBlock=terrainBlock==null?null:terrainBlock.immutable(); }
    }
    public record WheelEvidence(BlockPos wheelPosition, UUID physicalId, double tireRadius,
            double suspensionCompression, double nativeExtension, List<RayHit> rays) {
        public WheelEvidence { wheelPosition=wheelPosition==null?null:wheelPosition.immutable(); rays=List.copyOf(rays); }
    }
    public static GroundSurfaceObservation fromEvidence(UUID id,long tick,long nanos,int expected,List<WheelEvidence> wheels) {
        List<WheelEvidence> evidence=wheels==null?List.of():List.copyOf(wheels);
        String failure=null;
        double plane=Double.NaN;
        if(id==null || tick<0 || expected<3 || expected>32 || evidence.size()!=expected)
            failure="ground_surface_wheel_set_incomplete";
        var positions=new HashSet<BlockPos>();
        if(failure==null) for(WheelEvidence wheel:evidence) {
            if(wheel.wheelPosition()==null || !positions.add(wheel.wheelPosition()) || !id.equals(wheel.physicalId())) {
                failure="ground_surface_wheel_binding_invalid"; break;
            }
            double expectedCompression=.65-Math.max(0,Math.min(.65,wheel.nativeExtension()+.10833333333333334-wheel.tireRadius()));
            if(!Double.isFinite(wheel.tireRadius()) || wheel.tireRadius()<=0 || !Double.isFinite(wheel.nativeExtension())
                    || wheel.nativeExtension()<=1e-5 || !Double.isFinite(wheel.suspensionCompression())
                    || wheel.suspensionCompression()<=1e-5 || Math.abs(expectedCompression-wheel.suspensionCompression())>1e-6) {
                failure="ground_surface_wheel_not_supported"; break;
            }
            if(wheel.rays().size()!=3) { failure="ground_surface_ray_set_incomplete"; break; }
            var indices=new HashSet<Integer>();
            double minimum=5;
            for(RayHit ray:wheel.rays()) {
                if(ray.rayIndex() < -1 || ray.rayIndex()>1 || !indices.add(ray.rayIndex()) || !ray.hit()
                        || !ray.staticTerrain() || !ray.upwardFace() || !ray.wholePathLoaded() || ray.terrainBlock()==null
                        || !finite(ray.worldStart()) || !finite(ray.worldEnd()) || !finite(ray.worldHit())
                        || !Double.isFinite(ray.localExtension()) || ray.localExtension()<=1e-5 || ray.localExtension()>5+1e-6
                        || !onSegment(ray.worldStart(),ray.worldEnd(),ray.worldHit())) {
                    failure="ground_surface_ray_unproven"; break;
                }
                minimum=Math.min(minimum,ray.localExtension());
                if(!Double.isFinite(plane)) plane=ray.worldHit().y;
                else if(Math.abs(plane-ray.worldHit().y)>1e-5) { failure="ground_surface_not_one_horizontal_plane"; break; }
            }
            if(failure!=null)break;
            if(Math.abs(minimum-wheel.nativeExtension())>1e-6) { failure="ground_surface_native_extension_mismatch"; break; }
        }
        return new GroundSurfaceObservation(id,failure==null?plane:Double.NaN,tick,nanos,expected,evidence,
                failure==null,failure==null?"observed_static_surface":failure);
    }
    public boolean freshAt(long gameTick,long nowNanos) {
        long age=nowNanos-observedNanos;
        return available && physicalId!=null && Double.isFinite(planeY) && gameTick==observedGameTick && age>=0 && age<=250_000_000L;
    }
    private static boolean finite(Vec3 value) {
        return value!=null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
    private static boolean onSegment(Vec3 start,Vec3 end,Vec3 hit) {
        Vec3 segment=end.subtract(start); double length=segment.lengthSqr();
        if(!Double.isFinite(length) || length<1e-10 || length>25+1e-5)return false;
        double fraction=hit.subtract(start).dot(segment)/length;
        return fraction>=0 && fraction<=1 && start.add(segment.scale(fraction)).distanceToSqr(hit)<=1e-10
                && start.distanceToSqr(hit)>=.05*.05;
    }
    public static GroundSurfaceObservation unavailable(String reason) {
        return new GroundSurfaceObservation(null,Double.NaN,-1,-1,0,List.of(),false,reason);
    }
}
