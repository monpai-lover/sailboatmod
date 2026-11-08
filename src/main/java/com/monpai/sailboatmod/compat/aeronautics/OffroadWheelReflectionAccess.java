package com.monpai.sailboatmod.compat.aeronautics;

import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponentType;
import org.joml.Vector3dc;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Read-only adapter for Offroad 1.3.2 native suspension casts; never applies force or edits wheel internals. */
final class OffroadWheelReflectionAccess {
    record Evidence(double tireRadius, double suspensionCompression, boolean supported) { }
    private final Class<?> wheelType;
    private final Method heldItem, getComponent, radius, wheelAxis, terrainCast, maxExtension, interactingBlock;
    private final Field tireComponent;
    private OffroadWheelReflectionAccess(SableReflectionAccess.ClassLookup lookup) throws ReflectiveOperationException {
        wheelType=lookup.find("dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity");
        Class<?> pose=lookup.find("dev.ryanhcode.sable.companion.math.Pose3dc");
        Class<?> tire=lookup.find("dev.ryanhcode.offroad.content.components.TireLike");
        heldItem=wheelType.getMethod("getHeldItem");
        getComponent=heldItem.getReturnType().getMethod("get",DataComponentType.class);
        radius=tire.getMethod("radius");
        tireComponent=lookup.find("dev.ryanhcode.offroad.index.OffroadDataComponents").getField("TIRE");
        wheelAxis=wheelType.getDeclaredMethod("getRotatedWheelAxis",Vec3i.class);
        terrainCast=wheelType.getDeclaredMethod("computeMaxExtensionToTerrain",Vector3dc.class,pose);
        maxExtension=terrainCast.getReturnType().getDeclaredMethod("maxExtension");
        interactingBlock=terrainCast.getReturnType().getDeclaredMethod("minInteractingBlock");
        wheelAxis.setAccessible(true);terrainCast.setAccessible(true);maxExtension.setAccessible(true);interactingBlock.setAccessible(true);
    }
    static OffroadWheelReflectionAccess bindIfAvailable(SableReflectionAccess.ClassLookup lookup) {
        try { return new OffroadWheelReflectionAccess(lookup); }
        catch (ReflectiveOperationException | LinkageError | RuntimeException missing) { return null; }
    }
    Evidence observe(Object wheel,Object pose,Direction axleFacing) throws ReflectiveOperationException {
        if (!wheelType.isInstance(wheel) || axleFacing == null || axleFacing.getAxis().isVertical())
            throw new ReflectiveOperationException("wrong_native_wheel");
        double r = tireRadius(wheel);
        if (r == 0) return new Evidence(0,0,false);
        Object cast=terrainCast.invoke(wheel,travelAxis(wheel,axleFacing),pose);
        double extension=((Number)maxExtension.invoke(cast)).doubleValue();
        if (!Double.isFinite(extension)) throw new ReflectiveOperationException("invalid_native_suspension");
        // These are the exact native spring constants in WheelMountBlockEntity.sable$physicsTick.
        double compression=.65-Math.max(0,Math.min(.65,extension+.10833333333333334-r));
        boolean supported=interactingBlock.invoke(cast)!=null && extension>0 && compression>1e-5;
        return new Evidence(r, supported ? compression : 0, supported);
    }
    boolean isWheel(Object blockEntity) {
        return wheelType.isInstance(blockEntity);
    }

    double tireRadius(Object wheel) throws ReflectiveOperationException {
        Object tire = getComponent.invoke(heldItem.invoke(wheel), tireComponent.get(null));
        if (tire == null) return 0;
        double value = ((Number) radius.invoke(tire)).doubleValue();
        if (!Double.isFinite(value) || value <= 0) throw new ReflectiveOperationException("invalid_tire_radius");
        return value;
    }

    Vector3dc travelAxis(Object wheel, Direction axleFacing) throws ReflectiveOperationException {
        Direction axis=Direction.get(Direction.AxisDirection.POSITIVE,axleFacing.getAxis());
        Vec3i normal=axis.getNormal();
        return (Vector3dc)wheelAxis.invoke(wheel,new Vec3i(normal.getZ(),0,normal.getX()));
    }
}
