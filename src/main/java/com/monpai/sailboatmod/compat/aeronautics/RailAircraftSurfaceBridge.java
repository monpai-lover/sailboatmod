package com.monpai.sailboatmod.compat.aeronautics;

import com.monpai.sailboatmod.transport.physical.NativeControlResult;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;
import java.util.Optional;
import java.util.UUID;

/** Explicitly bound ordinary Create surfaces, independent of Flight Control and motor networks. */
public final class RailAircraftSurfaceBridge {
    public record Lease(UUID owner, UUID physicalId, BlockPos bearingPosition, long generation) { }
    public record Result(boolean accepted, String reason, Lease lease) {
        static Result rejected(String reason) { return new Result(false, reason, null); }
    }
    public record Readback(double actualAngleDegrees, double targetAngleDegrees, double rateDegreesPerSecond,
                           boolean active, UUID owner, UUID physicalId, String reason) { }
    private static volatile RailAircraftSurfaceReflectionAccess access;
    private static volatile boolean attempted;
    private RailAircraftSurfaceBridge() { }

    public static boolean available(BlockEntity controller) {
        if (!loaded(controller)) return false;
        var api = api();
        try { return api != null && api.body(controller) != null; }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return false; }
    }
    public static Result claim(BlockEntity controller, UUID owner, UUID bodyId, BlockPos bearingPosition) {
        return claim(controller, owner, bodyId, bearingPosition, 0);
    }
    public static Result claim(BlockEntity controller, UUID owner, UUID bodyId, BlockPos bearingPosition, double neutralDegrees) {
        if (!loaded(controller)) return Result.rejected("surface_controller_not_loaded");
        if (owner == null || bodyId == null || bearingPosition == null) return Result.rejected("surface_invalid_binding");
        var guard = ownership(controller, owner, bodyId);
        if (!guard.success()) return Result.rejected(guard.reason());
        var api = api(); if (api == null) return Result.rejected("surface_native_abi_unavailable");
        try {
            var bearing = api.bearing(controller, bodyId, bearingPosition);
            var state = bearing.servo();
            boolean predecessorLive = state.bound() && state.controller() instanceof BlockEntity previous && loaded(previous);
            var acquired = state.claim(controller, controller.getLevel(), bearing.body(), owner, bodyId,
                    controller.getBlockPos().asLong(), api.angle(bearing.entity()), neutralDegrees,
                    controller.getLevel().getGameTime(), predecessorLive);
            if (!acquired.accepted()) return Result.rejected(acquired.reason());
            api.sync(bearing.entity());
            return new Result(true, "", new Lease(owner, bodyId, bearingPosition.immutable(), acquired.token().generation()));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return Result.rejected(reason(unavailable));
        }
    }
    public static NativeControlResult setTarget(BlockEntity controller, Lease lease, double angleDegrees, double rateDegreesPerSecond) {
        return command(controller, lease, 0, angleDegrees, rateDegreesPerSecond);
    }
    public static NativeControlResult hold(BlockEntity controller, Lease lease) { return command(controller, lease, 1, 0, 0); }
    /** Return to the configured neutral, retaining a finite-angle lock even when commands stop. */
    public static NativeControlResult release(BlockEntity controller, Lease lease) { return command(controller, lease, 2, 0, 0); }
    /** Explicit manual binding removal is the only operation that restores ordinary RPM-driven rotation. */
    public static NativeControlResult clearBinding(BlockEntity controller, Lease lease) { return command(controller, lease, 3, 0, 0); }
    private static NativeControlResult command(BlockEntity controller, Lease lease, int mode, double angle, double rate) {
        if (!loaded(controller) || !valid(lease)) return NativeControlResult.failure("surface_stale_lease");
        var guard = ownership(controller, lease.owner(), lease.physicalId());
        if (!guard.success()) return guard;
        var api = api(); if (api == null) return NativeControlResult.failure("surface_native_abi_unavailable");
        try {
            var b = api.bearing(controller, lease.physicalId(), lease.bearingPosition());
            var state = b.servo(); var token = token(lease); var world = controller.getLevel();
            if (!state.owns(controller, world, b.body(), token)) return NativeControlResult.failure("surface_stale_lease");
            var before = state.view(b.actual());
            NativeControlResult result = switch (mode) {
                case 0 -> state.target(controller, world, b.body(), token, angle, rate, world.getGameTime());
                case 1 -> state.hold(controller, world, b.body(), token, api.angle(b.entity()), world.getGameTime());
                case 2 -> state.neutral(controller, world, b.body(), token);
                case 3 -> state.clear(controller, world, b.body(), token) ? NativeControlResult.applied()
                        : NativeControlResult.failure("surface_stale_lease");
                default -> NativeControlResult.failure("surface_invalid_command");
            };
            if (result.success() && !before.equals(state.view(b.actual()))) api.sync(b.entity());
            return result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return NativeControlResult.failure(reason(unavailable));
        }
    }
    public static NativeControlResult status(BlockEntity controller, Lease lease) {
        var value = readback(controller, lease);
        if (value.isEmpty()) return NativeControlResult.failure("surface_stale_lease");
        String reason = value.get().reason();
        return reason.equals("surface_tracking") || reason.equals("surface_holding") || reason.equals("surface_neutral_hold")
                ? NativeControlResult.applied() : NativeControlResult.failure(reason);
    }
    public static Optional<Readback> readback(BlockEntity controller, Lease lease) {
        if (!loaded(controller) || !valid(lease)) return Optional.empty();
        var api = api(); if (api == null) return Optional.empty();
        try {
            var b = api.bearing(controller, lease.physicalId(), lease.bearingPosition());
            if (!b.servo().owns(controller, controller.getLevel(), b.body(), token(lease))) return Optional.empty();
            var v = b.servo().view(b.actual());
            return Optional.of(new Readback(v.actualAngleDegrees(), v.targetAngleDegrees(), v.rateDegreesPerSecond(),
                    v.active(), v.owner(), v.physicalId(), v.reason()));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return Optional.empty(); }
    }
    /** Called before native ordinary bearing tick. Unloading never unlocks the bearing into motor rotation. */
    public static void onNativeTick(BlockEntity bearing, RailAircraftSurfaceServo state, double currentAngle) {
        if (bearing == null || bearing.getLevel() == null || bearing.getLevel().isClientSide || !state.locked() || !state.bound()) return;
        boolean healthy = false; var api = api();
        try {
            if (api != null && state.controller() instanceof BlockEntity controller && loaded(controller)) {
                var token = state.token();
                healthy = (state.returningToNeutral() || RailAircraftMotionBridge.ownedBy(controller, token.owner(), token.bodyId()))
                        && RailAircraftMotionBridge.foreignFlightControlStatus(controller).success()
                        && api.bearing(controller, token.bodyId(), bearing).body() == state.body();
            }
            if (state.beforeTick(bearing.getLevel().getGameTime(), currentAngle, healthy) && api != null) api.sync(bearing);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            if (state.beforeTick(bearing.getLevel().getGameTime(), currentAngle, false) && api != null)
                try { api.sync(bearing); } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { }
        }
    }
    private static NativeControlResult ownership(BlockEntity controller, UUID owner, UUID bodyId) {
        if (!RailAircraftMotionBridge.ownedBy(controller, owner, bodyId)) return NativeControlResult.failure("surface_motion_lease_required");
        return RailAircraftMotionBridge.foreignFlightControlStatus(controller);
    }
    private static boolean valid(Lease lease) {
        return lease != null && lease.owner() != null && lease.physicalId() != null && lease.bearingPosition() != null && lease.generation() > 0;
    }
    private static RailAircraftSurfaceServo.Token token(Lease l) { return new RailAircraftSurfaceServo.Token(l.owner(), l.physicalId(), l.generation()); }
    private static boolean loaded(BlockEntity c) {
        if (c == null || c.isRemoved() || !(c.getLevel() instanceof ServerLevel world) || !world.getServer().isSameThread()) return false;
        var chunk = world.getChunkSource().getChunkNow(c.getBlockPos().getX() >> 4, c.getBlockPos().getZ() >> 4);
        return chunk != null && chunk.getBlockEntities().get(c.getBlockPos()) == c;
    }
    private static synchronized RailAircraftSurfaceReflectionAccess api() {
        if (access != null) return access;
        if (attempted) return null;
        var mods = ModList.get();
        if (mods == null || !mods.isLoaded("create") || !mods.isLoaded("sable") || !RailAircraftSurfaceCompatibility.applied()) return null;
        attempted = true;
        try { access = RailAircraftSurfaceReflectionAccess.bind(name -> Class.forName(name, false, RailAircraftSurfaceBridge.class.getClassLoader())); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { access = null; }
        return access;
    }
    private static String reason(Throwable failure) {
        String message = failure.getMessage();
        return message != null && message.startsWith("surface_") ? message : "surface_native_state_unavailable";
    }
}
