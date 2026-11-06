package com.monpai.sailboatmod.transport.rail.ui;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import java.util.*;

/** Live terminals are scoped to a server; saved business identity lives in freight SavedData. */
public final class RailTerminalRegistry {
    private static final Map<MinecraftServer, Map<String, Set<BlockPos>>> SERVERS = new WeakHashMap<>();
    private RailTerminalRegistry() {}
    public static synchronized void register(Level level, BlockPos pos) {
        if (level instanceof ServerLevel server) SERVERS.computeIfAbsent(server.getServer(), x -> new HashMap<>())
                .computeIfAbsent(level.dimension().location().toString(), x -> new LinkedHashSet<>()).add(pos.immutable());
    }
    public static synchronized void unregister(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server)) return;
        var dimensions = SERVERS.get(server.getServer());
        if (dimensions == null) return;
        var positions = dimensions.get(level.dimension().location().toString());
        if (positions != null) positions.remove(pos);
    }
    public static synchronized Set<BlockPos> get(Level level) {
        if (!(level instanceof ServerLevel server)) return Set.of();
        return Set.copyOf(SERVERS.getOrDefault(server.getServer(), Map.of())
                .getOrDefault(level.dimension().location().toString(), Set.of()));
    }
    public static synchronized void clear(MinecraftServer server) { SERVERS.remove(server); }
}
