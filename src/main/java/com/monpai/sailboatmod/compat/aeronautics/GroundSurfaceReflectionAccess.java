package com.monpai.sailboatmod.compat.aeronautics;

import com.monpai.sailboatmod.compat.flightcontrol.AircraftWheelContactCompatibility;
import com.monpai.sailboatmod.transport.physical.GroundSurfaceObservation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Position;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Vector3dc;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.BiPredicate;

/** Independent native per-ray observations. Does not read the aggregate cast's hit metadata. */
final class GroundSurfaceReflectionAccess {
    private final Object helper;
    private final Class<?> wheelType;
    private final Method heldTire,getTireComponent,tireRadius,wheelAxis,terrainCast,maxExtension,
            transformPosition,inversePosition,ignoreRayBody,containingAt;
    private final Field tireComponent;

    private GroundSurfaceReflectionAccess(SableReflectionAccess.ClassLookup lookup) throws ReflectiveOperationException {
        Class<?> sable=lookup.find("dev.ryanhcode.sable.Sable");
        Class<?> companion=lookup.find("dev.ryanhcode.sable.ActiveSableCompanion");
        Class<?> sublevel=lookup.find("dev.ryanhcode.sable.sublevel.SubLevel");
        Class<?> pose=lookup.find("dev.ryanhcode.sable.companion.math.Pose3dc");
        Class<?> clipExtension=lookup.find("dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension");
        wheelType=lookup.find("dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity");
        helper=sable.getField("HELPER").get(null);
        if(helper==null || !companion.isInstance(helper))throw new ReflectiveOperationException("native_ground_helper_unavailable");
        heldTire=wheelType.getMethod("getHeldItem");
        getTireComponent=heldTire.getReturnType().getMethod("get",DataComponentType.class);
        tireRadius=lookup.find("dev.ryanhcode.offroad.content.components.TireLike").getMethod("radius");
        tireComponent=lookup.find("dev.ryanhcode.offroad.index.OffroadDataComponents").getField("TIRE");
        wheelAxis=wheelType.getDeclaredMethod("getRotatedWheelAxis",Vec3i.class);
        terrainCast=wheelType.getDeclaredMethod("computeMaxExtensionToTerrain",Vector3dc.class,pose);
        maxExtension=terrainCast.getReturnType().getDeclaredMethod("maxExtension");
        transformPosition=pose.getMethod("transformPosition",Vec3.class);
        inversePosition=pose.getMethod("transformPositionInverse",Vec3.class);
        ignoreRayBody=clipExtension.getMethod("sable$setIgnoredSubLevel",sublevel);
        containingAt=companion.getMethod("getContaining",Level.class,Position.class);
        if(!wheelAxis.trySetAccessible() || !terrainCast.trySetAccessible() || !maxExtension.trySetAccessible())
            throw new IllegalAccessException("native_ground_ray_abi_unavailable");
    }

    static GroundSurfaceReflectionAccess bindIfAvailable(SableReflectionAccess.ClassLookup lookup) {
        try { return new GroundSurfaceReflectionAccess(lookup); }
        catch(ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return null; }
    }

    GroundSurfaceObservation.WheelEvidence observe(ServerLevel level,BlockEntity wheel,Object body,Object pose,UUID bodyId)
            throws ReflectiveOperationException {
        // This existing hook flag is set only after exact Offroad 1.3.2 version, original class SHA,
        // and native call-site verification. A similar-looking future ABI is not sufficient.
        if(!AircraftWheelContactCompatibility.isApplied())throw new IllegalStateException("ground_surface_native_version_unsupported");
        if(!wheelType.isInstance(wheel) || !wheel.getBlockState().hasProperty(BlockStateProperties.HORIZONTAL_FACING))
            throw new IllegalStateException("ground_surface_wrong_wheel");
        Direction facing=wheel.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING);
        Vec3i normal=Direction.get(Direction.AxisDirection.POSITIVE,facing.getAxis()).getNormal();
        Vector3dc axis=(Vector3dc)wheelAxis.invoke(wheel,new Vec3i(normal.getZ(),0,normal.getX()));
        if(axis==null || !Double.isFinite(axis.x()) || !Double.isFinite(axis.y()) || !Double.isFinite(axis.z()))
            throw new IllegalStateException("ground_surface_axis_unavailable");
        Vec3 center=Vec3.atCenterOf(wheel.getBlockPos().relative(facing));
        var rays=new ArrayList<GroundSurfaceObservation.RayHit>();
        // Same local starts, five-block travel and ignored own body as the inspected native cast.
        // Every path is checked before either native cast; these reads never request chunks.
        for(int i=-1;i<=1;i++) {
            Vec3 start=center.add(axis.x()*i,axis.y()*i,axis.z()*i);
            Vec3 end=start.add(0,-5,0);
            Vec3 a=(Vec3)transformPosition.invoke(pose,start),b=(Vec3)transformPosition.invoke(pose,end);
            if(!fullRayPathLoaded(a,b,(x,z)->level.getChunkSource().getChunkNow(x,z)!=null))
                throw new IllegalStateException("ground_surface_terrain_unloaded");
        }
        Object stack=heldTire.invoke(wheel);
        Object tire=stack==null?null:getTireComponent.invoke(stack,tireComponent.get(null));
        if(tire==null)throw new IllegalStateException("ground_surface_tire_unavailable");
        double radius=((Number)tireRadius.invoke(tire)).doubleValue();
        Object aggregate=terrainCast.invoke(wheel,axis,pose);
        double extension=((Number)maxExtension.invoke(aggregate)).doubleValue();
        // Aggregate hit normal/body/block fields can belong to the final ray, not the nearest.
        // Only the numeric native minimum is cross-checked; all surface metadata comes below.
        for(int i=-1;i<=1;i++) {
            Vec3 start=center.add(axis.x()*i,axis.y()*i,axis.z()*i),end=start.add(0,-5,0);
            Vec3 a=(Vec3)transformPosition.invoke(pose,start),b=(Vec3)transformPosition.invoke(pose,end);
            ClipContext context=new ClipContext(start,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,CollisionContext.empty());
            ignoreRayBody.invoke(context,body); // Configures this new query only; never changes the body or wheel.
            var hit=level.clip(context);
            boolean actual=hit.getType()==HitResult.Type.BLOCK;
            Vec3 worldHit=actual?hit.getLocation():null;
            Object hitBody=actual?containingAt.invoke(helper,level,worldHit):body;
            Vec3 localHit=actual?(Vec3)inversePosition.invoke(pose,worldHit):null;
            double distance=actual?center.y-localHit.y:Double.NaN;
            rays.add(new GroundSurfaceObservation.RayHit(i,a,b,worldHit,actual?hit.getBlockPos():null,distance,
                    actual,actual&&hitBody==null,actual&&hit.getDirection()==Direction.UP,true));
        }
        double compression=.65-Math.max(0,Math.min(.65,extension+.10833333333333334-radius));
        return new GroundSurfaceObservation.WheelEvidence(wheel.getBlockPos(),bodyId,radius,compression,extension,rays);
    }

    /** Conservative whole-path chunk rectangle, including diagonal corner crossings. */
    static boolean fullRayPathLoaded(Vec3 start,Vec3 end,BiPredicate<Integer,Integer> loaded) {
        if(!finite(start) || !finite(end))return false;
        double length=start.distanceToSqr(end);
        if(!Double.isFinite(length) || length<1e-10 || length>25+1e-5)return false;
        // BlockGetter.traverseBlocks in Minecraft 1.21.1 expands both endpoints with
        // these exact lerps before its first block read, including across chunk edges.
        double fromX=Mth.lerp(-1.0E-7,start.x,end.x),toX=Mth.lerp(-1.0E-7,end.x,start.x);
        double fromZ=Mth.lerp(-1.0E-7,start.z,end.z),toZ=Mth.lerp(-1.0E-7,end.z,start.z);
        int minX=(int)Math.floor(Math.min(fromX,toX)/16),maxX=(int)Math.floor(Math.max(fromX,toX)/16);
        int minZ=(int)Math.floor(Math.min(fromZ,toZ)/16),maxZ=(int)Math.floor(Math.max(fromZ,toZ)/16);
        if(maxX-minX>1 || maxZ-minZ>1)return false;
        for(long x=minX;x<=maxX;x++)for(long z=minZ;z<=maxZ;z++)if(!loaded.test((int)x,(int)z))return false;
        return true;
    }
    private static boolean finite(Vec3 v) { return v!=null&&Double.isFinite(v.x)&&Double.isFinite(v.y)&&Double.isFinite(v.z); }
}
