package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import java.util.*;

/** Main-thread-owned, per-server ledger. Only the owner can look up or mutate a task. */
public final class RouteTaskRegistry {
    public static final int MAX_ACTIVE = 4;
    public static final int MAX_HISTORY = 16;
    private final Map<UUID, LinkedHashMap<UUID, RouteTaskView>> owners = new HashMap<>();

    public boolean begin(UUID owner, UUID id, String dimension, BlockPos source, BlockPos target, long now) {
        var tasks = owners.computeIfAbsent(owner, ignored -> new LinkedHashMap<>());
        if (tasks.containsKey(id) || tasks.values().stream().filter(RouteTaskView::active).count() >= MAX_ACTIVE
                || tasks.values().stream().anyMatch(t -> t.active() && t.dimension().equals(dimension)
                    && t.source().equals(source) && t.target().equals(target))) return false;
        tasks.put(id, new RouteTaskView(id, dimension, source, target, 0, 0, "排队 / 异步载入码头", now, now));
        prune(tasks);
        return true;
    }

    public boolean update(UUID owner, UUID id, int percent, int state, String stage, long now) {
        var tasks = owners.get(owner);
        RouteTaskView previous = tasks == null ? null : tasks.get(id);
        if (previous == null || !previous.active()) return false;
        tasks.put(id, new RouteTaskView(id, previous.dimension(), previous.source(), previous.target(),
                Math.max(previous.percent(), percent), state, stage, previous.startedMillis(), now));
        prune(tasks);
        return true;
    }

    public RouteTaskView get(UUID owner, UUID id) {
        var tasks = owners.get(owner);
        return tasks == null ? null : tasks.get(id);
    }

    public List<RouteTaskView> list(UUID owner) {
        var tasks = owners.get(owner);
        if (tasks == null) return List.of();
        List<RouteTaskView> result = new ArrayList<>(tasks.values());
        Collections.reverse(result);
        return List.copyOf(result);
    }

    private static void prune(LinkedHashMap<UUID, RouteTaskView> tasks) {
        var iterator = tasks.entrySet().iterator();
        while (tasks.size() > MAX_HISTORY && iterator.hasNext()) {
            if (!iterator.next().getValue().active()) iterator.remove();
        }
    }
}
