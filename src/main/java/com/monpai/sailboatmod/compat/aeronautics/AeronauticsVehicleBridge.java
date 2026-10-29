package com.monpai.sailboatmod.compat.aeronautics;

import com.monpai.sailboatmod.transport.physical.NativeControlResult;
import com.monpai.sailboatmod.transport.physical.PhysicalVehicleView;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** All optional names and reflection are loaded on first server use, never during normal mod startup. */
public final class AeronauticsVehicleBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(AeronauticsVehicleBridge.class);
    private static final AtomicBoolean WARNED = new AtomicBoolean();
    private static volatile boolean attempted;
    private static volatile SableReflectionAccess access;

    private AeronauticsVehicleBridge() { }

    public static Optional<PhysicalVehicleView> resolve(@Nullable BlockEntity controller, @Nullable Direction facing) {
        if (!serverThread(controller) || facing == null) return Optional.empty();
        SableReflectionAccess api = api();
        if (api == null) return Optional.empty();
        try {
            Object body = bodyFor(api, controller);
            if (body == null) return Optional.empty();
            ServerLevel level = (ServerLevel) controller.getLevel();
            return Optional.of(api.snapshot(body, controller.getBlockPos(), facing, level.dimension().location().toString()));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            warn(failure);
            return Optional.empty();
        }
    }

    public static Optional<ServerLevel> mainLevel(@Nullable BlockEntity controller) {
        if (!serverThread(controller)) return Optional.empty();
        SableReflectionAccess api = api();
        if (api == null) return Optional.empty();
        try {
            return bodyFor(api, controller) == null ? Optional.empty() : Optional.of((ServerLevel) controller.getLevel());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            warn(failure);
            return Optional.empty();
        }
    }

    public static boolean sameStructure(@Nullable BlockEntity controller, @Nullable BlockEntity target) {
        if (!serverThread(controller) || !serverThread(target) || controller.getLevel() != target.getLevel()) return false;
        SableReflectionAccess api = api();
        if (api == null) return false;
        try {
            return api.sameLiveStructure(bodyFor(api, controller), bodyFor(api, target));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            warn(failure);
            return false;
        }
    }

    public static NativeControlResult nativeThrottle(@Nullable BlockEntity controller, @Nullable BlockEntity target, int signal) {
        return nativeControl(controller, target, signal, 0, true);
    }

    public static NativeControlResult nativeSteering(@Nullable BlockEntity controller, @Nullable BlockEntity target, float angleDegrees) {
        return nativeControl(controller, target, 0, angleDegrees, false);
    }

    private static NativeControlResult nativeControl(@Nullable BlockEntity controller, @Nullable BlockEntity target,
                                                     int signal, float angle, boolean throttle) {
        if (!serverThread(controller) || !serverThread(target)) return NativeControlResult.failure("not_server_or_removed");
        if (controller.getLevel() != target.getLevel()) return NativeControlResult.failure("target_not_in_same_structure");
        SableReflectionAccess api = api();
        if (api == null) return NativeControlResult.failure("optional_mod_unavailable");
        try {
            Object controllerBody = bodyFor(api, controller);
            Object targetBody = bodyFor(api, target);
            return throttle ? api.throttle(controllerBody, targetBody, target, signal)
                    : api.steering(controllerBody, targetBody, target, angle);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            warn(failure);
            return NativeControlResult.failure("native_control_failed");
        }
    }

    private static Object bodyFor(SableReflectionAccess api, BlockEntity entity) throws ReflectiveOperationException {
        Object body = api.containing(entity);
        return body != null && api.levelOf(body) == entity.getLevel() ? body : null;
    }

    private static boolean serverThread(@Nullable BlockEntity entity) {
        return entity != null && !entity.isRemoved() && entity.getLevel() instanceof ServerLevel level
                && level.getServer() != null && level.getServer().isSameThread();
    }

    @Nullable
    private static SableReflectionAccess api() {
        if (attempted) return access;
        ModList mods = ModList.get();
        if (mods == null || !mods.isLoaded("sable") || !mods.isLoaded("aeronautics") || !mods.isLoaded("simulated")) return null;
        synchronized (AeronauticsVehicleBridge.class) {
            if (attempted) return access;
            try {
                ClassLoader loader = AeronauticsVehicleBridge.class.getClassLoader();
                access = SableReflectionAccess.bind(name -> Class.forName(name, false, loader));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                warn(failure);
            }
            attempted = true;
            return access;
        }
    }

    private static void warn(Throwable failure) {
        if (WARNED.compareAndSet(false, true)) {
            LOGGER.warn("[PhysicalTransport] Aeronautics/Sable API unavailable; physical transport must remain paused: {}", failure.toString());
        }
    }
}
