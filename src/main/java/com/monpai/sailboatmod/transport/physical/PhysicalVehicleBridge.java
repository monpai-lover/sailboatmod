package com.monpai.sailboatmod.transport.physical;

import com.monpai.sailboatmod.compat.aeronautics.AeronauticsVehicleBridge;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.capabilities.Capabilities;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional, server-only access to native physical structures and explicitly bound real actuators. */
public final class PhysicalVehicleBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(PhysicalVehicleBridge.class);
    private static final AtomicBoolean WARNED_CARGO = new AtomicBoolean();

    private PhysicalVehicleBridge() { }

    public static Optional<PhysicalVehicleView> resolve(@Nullable BlockEntity controller, @Nullable Direction facing) {
        return AeronauticsVehicleBridge.resolve(controller, facing);
    }
    public static Optional<Vec3> worldPosition(@Nullable BlockEntity controller) {
        return resolve(controller, Direction.NORTH).filter(PhysicalVehicleView::available).map(PhysicalVehicleView::worldPosition);
    }
    public static Optional<ServerLevel> mainLevel(@Nullable BlockEntity controller) {
        return AeronauticsVehicleBridge.mainLevel(controller);
    }
    public static boolean sameStructure(@Nullable BlockEntity controller, @Nullable BlockEntity target) {
        return AeronauticsVehicleBridge.sameStructure(controller, target);
    }
    public static Optional<IItemHandler> itemHandler(@Nullable BlockEntity controller, @Nullable BlockEntity cargo, @Nullable Direction side) {
        if (!sameStructure(controller, cargo)) return Optional.empty();
        // Capability access uses the cargo's storage/plot position, never its transformed world position.
        try {
            return mainLevel(controller).flatMap(level -> Optional.ofNullable(level.getCapability(
                    Capabilities.ItemHandler.BLOCK, cargo.getBlockPos(), cargo.getBlockState(), cargo, side)));
        } catch (RuntimeException | LinkageError failure) {
            if (WARNED_CARGO.compareAndSet(false, true)) {
                LOGGER.warn("[PhysicalTransport] Bound cargo capability unavailable; loading must pause: {}", failure.toString());
            }
            return Optional.empty();
        }
    }
    public static NativeControlResult nativeThrottle(@Nullable BlockEntity controller, @Nullable BlockEntity target, int signal) {
        return AeronauticsVehicleBridge.nativeThrottle(controller, target, signal);
    }
    public static NativeControlResult nativeSteering(@Nullable BlockEntity controller, @Nullable BlockEntity target, float angleDegrees) {
        return AeronauticsVehicleBridge.nativeSteering(controller, target, angleDegrees);
    }
}
