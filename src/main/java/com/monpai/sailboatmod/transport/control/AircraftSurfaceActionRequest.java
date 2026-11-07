package com.monpai.sailboatmod.transport.control;

import com.monpai.sailboatmod.transport.air.surface.AircraftSurfaceBinding;
import com.monpai.sailboatmod.transport.air.surface.AircraftSurfaceBinding.Role;
import net.minecraft.core.BlockPos;

/** Untrusted packet coordinates are plot positions; only a bounded local binding is retained. */
public final class AircraftSurfaceActionRequest {
    private AircraftSurfaceActionRequest() { }
    public record Key(Role role,BlockPos localOffset) { }
    public static AircraftSurfaceBinding bind(String value,BlockPos controller) {
        String[] p=parts(value,6);Key key=key(p[0]+";"+p[1],controller);
        return new AircraftSurfaceBinding(key.role(),key.localOffset(),Double.parseDouble(p[2]),
                Integer.parseInt(p[3]),Double.parseDouble(p[4]),Double.parseDouble(p[5]));
    }
    public static Key key(String value,BlockPos controller) {
        if(controller==null)throw invalid();String[] p=parts(value,2),xyz=p[1].split(",",-1);
        if(xyz.length!=3)throw invalid();
        try {
            int x=Math.subtractExact(Integer.parseInt(xyz[0]),controller.getX());
            int y=Math.subtractExact(Integer.parseInt(xyz[1]),controller.getY());
            int z=Math.subtractExact(Integer.parseInt(xyz[2]),controller.getZ());
            if(Math.abs((long)x)>128||Math.abs((long)y)>128||Math.abs((long)z)>128)throw invalid();
            return new Key(Role.valueOf(p[0]),new BlockPos(x,y,z));
        }catch(ArithmeticException failure){throw invalid();}
    }
    private static String[] parts(String value,int count) {
        if(value==null||value.length()>256)throw invalid();String[] p=value.split(";",-1);
        if(p.length!=count)throw invalid();return p;
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("surface_invalid_binding");}
}
