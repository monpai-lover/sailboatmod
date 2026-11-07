package com.monpai.sailboatmod.compat.aeronautics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponentType;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OffroadWheelReflectionAccessTest {
    private OffroadWheelReflectionAccess access() {
        return OffroadWheelReflectionAccess.bindIfAvailable(name -> switch(name) {
            case "dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity" -> Wheel.class;
            case "dev.ryanhcode.sable.companion.math.Pose3dc" -> Pose.class;
            case "dev.ryanhcode.offroad.content.components.TireLike" -> Tire.class;
            case "dev.ryanhcode.offroad.index.OffroadDataComponents" -> Components.class;
            default -> throw new ClassNotFoundException(name);
        });
    }
    @Test void nativeTerrainHitRequiresRealTireAndPositiveSuspensionLoad() throws Exception {
        var api=access();assertNotNull(api);var wheel=new Wheel();
        var contact=api.observe(wheel,new Pose(),Direction.NORTH);
        assertTrue(contact.supported());assertEquals(.75,contact.tireRadius());assertTrue(contact.suspensionCompression()>0);
        wheel.extension=5;assertFalse(api.observe(wheel,new Pose(),Direction.NORTH).supported());
        wheel.extension=1.5;assertFalse(api.observe(wheel,new Pose(),Direction.NORTH).supported(),"nearby terrain beyond loaded suspension is not support");
        wheel.extension=.8;wheel.tire=null;assertFalse(api.observe(wheel,new Pose(),Direction.NORTH).supported());
    }
    @Test void nativeCastWithoutHitAndChangedOptionalApisRemainUnavailable() throws Exception {
        var wheel=new Wheel();wheel.hit=null;
        assertFalse(access().observe(wheel,new Pose(),Direction.NORTH).supported());
        assertNull(OffroadWheelReflectionAccess.bindIfAvailable(name -> Object.class));
    }
    public static final class Pose { }
    public static final class Components { public static final DataComponentType<Object> TIRE=null; }
    public static final class Tire { public float radius() { return .75F; } }
    public static final class Stack { private final Object tire;Stack(Object tire){this.tire=tire;}public Object get(DataComponentType<?> component){return tire;} }
    public static final class Wheel {
        Object tire=new Tire();double extension=.8;BlockPos hit=BlockPos.ZERO;
        public Stack getHeldItem(){return new Stack(tire);}
        private Vector3dc getRotatedWheelAxis(Vec3i axis){return new Vector3d(axis.getX(),axis.getY(),axis.getZ());}
        private Cast computeMaxExtensionToTerrain(Vector3dc axis,Pose pose){return new Cast(extension,hit);}
    }
    private record Cast(double maxExtension,BlockPos minInteractingBlock) { }
}
