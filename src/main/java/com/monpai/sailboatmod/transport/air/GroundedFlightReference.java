package com.monpai.sailboatmod.transport.air;

import com.monpai.sailboatmod.transport.physical.GroundSurfaceObservation;
import net.minecraft.core.BlockPos;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Transient evidence for a new flight; never a persisted calibration or an airborne correction. */
public final class GroundedFlightReference {
    private static final long STABLE_TICKS=40, MAX_GAP_TICKS=10, MAX_FRAME_AGE_NANOS=250_000_000L;
    private static final double OFFSET_SPREAD=.03, PLANE_TOLERANCE=1e-5;
    public record Frame(UUID physicalId, double referenceY, double speed, double angularSpeed,
            long sampledNanos, boolean ready, int registeredWheelCount, Object configuration) { }
    public record Measurement(UUID physicalId, double planeY, double referenceAboveSurface,
            long observedGameTick,long surfaceNanos,long nativeNanos) {
        public boolean freshAt(long tick,long nowNanos) {
            return tick==observedGameTick && nowNanos-surfaceNanos>=0 && nowNanos-surfaceNanos<=MAX_FRAME_AGE_NANOS
                    && nowNanos-nativeNanos>=0 && nowNanos-nativeNanos<=MAX_FRAME_AGE_NANOS;
        }
        public boolean matchesSurface(double surfaceY) {
            return Double.isFinite(surfaceY) && Math.abs(surfaceY-planeY)<=PLANE_TOLERANCE;
        }
    }
    private record Identity(UUID body, Map<BlockPos,Double> tires, Object configuration) { }
    private Identity identity;
    private long firstTick, lastTick, lastNativeNanos, lastObservedNanos;
    private double planeY, minimumOffset, maximumOffset;

    public Optional<Measurement> observe(GroundSurfaceObservation surface, Frame frame, long tick, long nowNanos) {
        if(surface==null || frame==null || !surface.freshAt(tick,nowNanos) || !frame.ready()
                || frame.physicalId()==null || !frame.physicalId().equals(surface.physicalId()) || frame.configuration()==null
                || frame.registeredWheelCount()<3 || frame.registeredWheelCount()>32
                || frame.registeredWheelCount()!=surface.expectedWheelCount() || surface.wheels().size()!=frame.registeredWheelCount()
                || !Double.isFinite(frame.referenceY()) || !Double.isFinite(frame.speed()) || frame.speed()<0 || frame.speed()>.25
                || !Double.isFinite(frame.angularSpeed()) || frame.angularSpeed()<0 || frame.angularSpeed()>Math.toRadians(2)
                || nowNanos-frame.sampledNanos()<0 || nowNanos-frame.sampledNanos()>MAX_FRAME_AGE_NANOS) {
            reset();return Optional.empty();
        }
        double offset=frame.referenceY()-surface.planeY();
        if(!Double.isFinite(offset) || offset<0 || offset>64) { reset();return Optional.empty(); }
        var tires=new HashMap<BlockPos,Double>();
        for(var wheel:surface.wheels()) {
            if(wheel.wheelPosition()==null || !frame.physicalId().equals(wheel.physicalId())
                    || !Double.isFinite(wheel.tireRadius()) || wheel.tireRadius()<=0
                    || tires.put(wheel.wheelPosition().immutable(),wheel.tireRadius())!=null) { reset();return Optional.empty(); }
        }
        var current=new Identity(frame.physicalId(),Map.copyOf(tires),frame.configuration());
        boolean changed=!Objects.equals(identity,current) || tick<lastTick || tick-lastTick>MAX_GAP_TICKS
                || nowNanos<lastObservedNanos || frame.sampledNanos()<lastNativeNanos
                || Math.abs(planeY-surface.planeY())>PLANE_TOLERANCE
                || Math.max(maximumOffset,offset)-Math.min(minimumOffset,offset)>OFFSET_SPREAD;
        if(changed) {
            identity=current;firstTick=lastTick=tick;lastNativeNanos=frame.sampledNanos();lastObservedNanos=nowNanos;
            planeY=surface.planeY();minimumOffset=maximumOffset=offset;return Optional.empty();
        }
        // A duplicate game tick or frozen native frame cannot advance stability or yield a usable proof.
        if(tick==lastTick || frame.sampledNanos()==lastNativeNanos)return Optional.empty();
        lastTick=tick;lastNativeNanos=frame.sampledNanos();lastObservedNanos=nowNanos;
        minimumOffset=Math.min(minimumOffset,offset);maximumOffset=Math.max(maximumOffset,offset);
        return tick-firstTick>=STABLE_TICKS
                ? Optional.of(new Measurement(frame.physicalId(),surface.planeY(),offset,tick,surface.observedNanos(),frame.sampledNanos())) : Optional.empty();
    }
    public void reset() { identity=null;firstTick=lastTick=lastNativeNanos=lastObservedNanos=0; }
}
