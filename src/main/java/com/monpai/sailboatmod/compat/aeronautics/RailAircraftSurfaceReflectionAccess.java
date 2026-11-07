package com.monpai.sailboatmod.compat.aeronautics;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/** Optional names are resolved only after Create and Sable presence checks. No Flight Control dependency. */
final class RailAircraftSurfaceReflectionAccess {
    @FunctionalInterface interface Lookup { Class<?> find(String name) throws ClassNotFoundException; }
    record Bearing(BlockEntity entity, Object body, RailAircraftSurfaceServo servo, double actual) { }
    private final Class<?> bearingType, bodyType, entityType, contraptionType;
    private final Object helper;
    private final Method containing, uniqueId, removed, bodyLevel, running, moved, getContraption, entityAngle, bearingAngle, sendData;
    static RailAircraftSurfaceReflectionAccess bind(Lookup lookup) throws ReflectiveOperationException {
        return new RailAircraftSurfaceReflectionAccess(lookup);
    }
    private RailAircraftSurfaceReflectionAccess(Lookup lookup) throws ReflectiveOperationException {
        bearingType = lookup.find(RailAircraftSurfaceCompatibility.BEARING);
        entityType = lookup.find(RailAircraftSurfaceCompatibility.CONTRAPTION);
        for (var pin : RailAircraftSurfaceCompatibility.PINS.entrySet()) hash(lookup.find(pin.getKey()), pin.getValue());
        bodyType = lookup.find(RailAircraftMotionCompatibility.BODY);
        hash(bodyType, RailAircraftMotionCompatibility.PINNED_CLASSES.get(RailAircraftMotionCompatibility.BODY));
        contraptionType = lookup.find("com.simibubi.create.content.contraptions.bearing.BearingContraption");
        helper = lookup.find("dev.ryanhcode.sable.Sable").getField("HELPER").get(null);
        containing = lookup.find("dev.ryanhcode.sable.ActiveSableCompanion").getMethod("getContaining", BlockEntity.class);
        uniqueId = bodyType.getMethod("getUniqueId"); removed = bodyType.getMethod("isRemoved"); bodyLevel = bodyType.getMethod("getLevel");
        running = bearingType.getMethod("isRunning"); moved = bearingType.getMethod("getMovedContraption");
        getContraption = entityType.getMethod("getContraption"); entityAngle = entityType.getMethod("getAngle", float.class);
        bearingAngle = bearingType.getMethod("getInterpolatedAngle", float.class); sendData = bearingType.getMethod("sendData");
        if (helper == null || !RailAircraftSurfaceAccess.class.isAssignableFrom(bearingType))
            throw new ReflectiveOperationException("surface_native_hook_unavailable");
    }
    Object body(BlockEntity entity) throws ReflectiveOperationException {
        Object body = call(containing, helper, entity);
        return body != null && body.getClass() == bodyType && !Boolean.TRUE.equals(call(removed, body))
                && call(bodyLevel, body) == entity.getLevel() ? body : null;
    }
    UUID id(Object body) throws ReflectiveOperationException { return (UUID) call(uniqueId, body); }
    Bearing bearing(BlockEntity controller, UUID expectedBody, BlockPos pos) throws ReflectiveOperationException {
        ServerLevel level = (ServerLevel) controller.getLevel();
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        BlockEntity entity = chunk == null ? null : chunk.getBlockEntities().get(pos);
        return bearing(controller, expectedBody, entity);
    }
    Bearing bearing(BlockEntity controller, UUID expectedBody, BlockEntity entity) throws ReflectiveOperationException {
        if (entity == null || entity.getClass() != bearingType || entity.isRemoved() || entity.getLevel() != controller.getLevel()
                || !(entity instanceof RailAircraftSurfaceAccess access))
            throw new ReflectiveOperationException("surface_ordinary_bearing_required");
        Object body = body(controller);
        if (body == null || body(entity) != body || !expectedBody.equals(id(body)))
            throw new ReflectiveOperationException("surface_foreign_body");
        if (!entity.getBlockState().hasProperty(BlockStateProperties.FACING))
            throw new ReflectiveOperationException("surface_hinge_axis_unavailable");
        // All three native axes are supported. Domain role validation rejects a forward-axis propeller.
        entity.getBlockState().getValue(BlockStateProperties.FACING).getAxis();
        Object part = call(moved, entity);
        Object contraption = part == null ? null : call(getContraption, part);
        if (!Boolean.TRUE.equals(call(running, entity)) || part == null || part.getClass() != entityType
                || ((Entity) part).isRemoved() || contraption == null || contraption.getClass() != contraptionType)
            throw new ReflectiveOperationException("surface_unassembled_bearing");
        double actual = ((Number) call(entityAngle, part, 1f)).doubleValue();
        if (!Double.isFinite(actual)) throw new ReflectiveOperationException("surface_invalid_actual_angle");
        return new Bearing(entity, body, access.sailboat$surfaceServo(), actual);
    }
    double angle(BlockEntity entity) throws ReflectiveOperationException {
        return ((Number) call(bearingAngle, entity, 0f)).doubleValue();
    }
    void sync(BlockEntity entity) throws ReflectiveOperationException { entity.setChanged(); call(sendData, entity); }
    private static Object call(Method method, Object target, Object... args) throws ReflectiveOperationException {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw new ReflectiveOperationException("surface_native_call_failed:" + method.getName(), failure.getCause()); }
    }
    private static void hash(Class<?> type, String pin) throws ReflectiveOperationException {
        try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            byte[] bytes = input == null ? null : input.readNBytes(1024 * 1024 + 1);
            if (bytes == null || bytes.length > 1024 * 1024 || !pin.equals(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes))))
                throw new ReflectiveOperationException("surface_unknown_native_abi:" + type.getName());
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException failure) {
            throw new ReflectiveOperationException("surface_native_abi_unavailable", failure);
        }
    }
}
