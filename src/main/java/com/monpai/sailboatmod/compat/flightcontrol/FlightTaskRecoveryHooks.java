package com.monpai.sailboatmod.compat.flightcontrol;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Common mixin callbacks are inert on clients, absent/unsupported FC, and unclaimed terminals. */
public final class FlightTaskRecoveryHooks {
    static final FlightTaskSuccessorRegistry REGISTRY = new FlightTaskSuccessorRegistry();
    private FlightTaskRecoveryHooks() { }
    static String key(ServerLevel level, net.minecraft.core.BlockPos pos) {
        return level.dimension().location() + "/" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
    public static FlightTaskSuccessorRegistry.Attempt before(BlockEntity terminal) {
        if (!FlightControlRecoverySupport.isApplied() || !(terminal.getLevel() instanceof ServerLevel level)
                || !level.getServer().isSameThread() || terminal.isRemoved()) return null;
        String key = key(level, terminal.getBlockPos());
        if (!REGISTRY.hasClaim(level.getServer(), key)) return null;
        try {
            var access = FlightControlReflection.open(level, terminal.getBlockPos());
            if (!access.isNativeRecoveryCandidate()) return null;
            return REGISTRY.begin(level.getServer(), key, access.ownershipState(), level.getGameTime());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return null; }
    }
    public static void after(BlockEntity terminal, FlightTaskSuccessorRegistry.Attempt attempt) {
        if (attempt == null || !(terminal.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) return;
        try {
            REGISTRY.finish(attempt, FlightControlReflection.open(level, terminal.getBlockPos()).ownershipState(), level.getGameTime());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { /* No proof is safer than guessing. */ }
    }
}
