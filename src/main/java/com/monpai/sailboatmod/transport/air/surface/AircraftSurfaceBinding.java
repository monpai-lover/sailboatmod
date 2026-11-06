package com.monpai.sailboatmod.transport.air.surface;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** An explicit controller-relative bearing binding. Polarity is additional to the role's differential sign. */
public record AircraftSurfaceBinding(Role role,BlockPos localOffset,double neutralAngleDeg,int polarity,double maxDeflectionDeg,double rateDegPerSecond,Mode mode) {
    public enum Role { ELEVATOR,AILERON_LEFT,AILERON_RIGHT,RUDDER,ELEVON_LEFT,ELEVON_RIGHT }
    public enum Mode { AUTO,HOLD }
    public static final int MAX_LOCAL_OFFSET=128;

    public AircraftSurfaceBinding {
        if(role==null||localOffset==null||mode==null||!Double.isFinite(neutralAngleDeg)||Math.abs(neutralAngleDeg)>180
                ||(polarity!=-1&&polarity!=1)||!Double.isFinite(maxDeflectionDeg)||maxDeflectionDeg<=0||maxDeflectionDeg>30
                ||!Double.isFinite(rateDegPerSecond)||rateDegPerSecond<=0||rateDegPerSecond>90
                ||Math.abs((long)localOffset.getX())>MAX_LOCAL_OFFSET||Math.abs((long)localOffset.getY())>MAX_LOCAL_OFFSET
                ||Math.abs((long)localOffset.getZ())>MAX_LOCAL_OFFSET)throw new IllegalArgumentException("surface_invalid_binding");
        localOffset=localOffset.immutable();
    }
    public AircraftSurfaceBinding(Role role,BlockPos offset,double neutral,int polarity,double max,double rate){this(role,offset,neutral,polarity,max,rate,Mode.AUTO);}

    /** Only checks declared geometry. The integration must still read and verify the actual bearing/body before claiming it. */
    public boolean axisCompatible(Direction forward,Direction.Axis actual) {
        if(forward==null||!forward.getAxis().isHorizontal()||actual==null)return false;
        return role==Role.RUDDER?actual==Direction.Axis.Y:actual==(forward.getAxis()==Direction.Axis.X?Direction.Axis.Z:Direction.Axis.X);
    }
}
