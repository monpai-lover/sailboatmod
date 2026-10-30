package com.monpai.sailboatmod.transport.air.airport;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import java.util.*;

/** Loaded airports only, scoped to a server rather than a global dimension name. */
public final class AirportRegistry {
    private static final Map<MinecraftServer, Map<String, Set<BlockPos>>> SERVERS = new WeakHashMap<>();
    private AirportRegistry() {}
    public static synchronized void register(Level level, BlockPos pos) {
        if (level instanceof ServerLevel server) SERVERS.computeIfAbsent(server.getServer(), ignored -> new HashMap<>())
                .computeIfAbsent(level.dimension().location().toString(), ignored -> new LinkedHashSet<>()).add(pos.immutable());
    }
    public static synchronized void unregister(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server)) return;
        Map<String, Set<BlockPos>> dimensions = SERVERS.get(server.getServer()); if (dimensions == null) return;
        Set<BlockPos> positions = dimensions.get(level.dimension().location().toString()); if (positions != null) positions.remove(pos);
    }
    public static synchronized Set<BlockPos> get(Level level) {
        if (!(level instanceof ServerLevel server)) return Set.of();
        Map<String, Set<BlockPos>> dimensions = SERVERS.get(server.getServer());
        return dimensions == null ? Set.of() : Set.copyOf(dimensions.getOrDefault(level.dimension().location().toString(), Set.of()));
    }
    public static synchronized void clear(MinecraftServer server) { SERVERS.remove(server); }
}
