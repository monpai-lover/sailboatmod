package com.monpai.sailboatmod.route.water;

import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WaterRouteTaskService {
    private static final int DEFAULT_TASKS_PER_TICK = 4;
    private static final WaterRouteTaskService GLOBAL = new WaterRouteTaskService();

    private final Map<String, WaterRouteTask> pendingTasks = new LinkedHashMap<>();
    private final int tasksPerTick;

    public WaterRouteTaskService() {
        this(DEFAULT_TASKS_PER_TICK);
    }

    WaterRouteTaskService(int tasksPerTick) {
        this.tasksPerTick = Math.max(1, tasksPerTick);
    }

    public static WaterRouteTaskService global() {
        return GLOBAL;
    }

    public WaterRouteResult<WaterRouteTask> submit(WaterRouteTask task) {
        if (task == null) {
            return WaterRouteResult.failure(WaterRouteFailureReason.NO_WATER_PATH);
        }
        if (pendingTasks.containsKey(task.key())) {
            return WaterRouteResult.failure(WaterRouteFailureReason.ALREADY_PENDING);
        }
        pendingTasks.put(task.key(), task);
        return WaterRouteResult.success(task);
    }

    public void tick() {
        tick(null);
    }

    public void tick(ServerLevel level) {
        if (pendingTasks.isEmpty()) {
            return;
        }
        List<String> completedKeys = new ArrayList<>();
        Iterator<WaterRouteTask> iterator = pendingTasks.values().iterator();
        int advanced = 0;
        while (iterator.hasNext() && advanced < tasksPerTick) {
            WaterRouteTask task = iterator.next();
            advanced++;
            if (level != null && !task.dimensionId().equals(level.dimension().location().toString())) {
                continue;
            }
            if (task.advance()) {
                completedKeys.add(task.key());
            }
        }
        completedKeys.forEach(pendingTasks::remove);
    }

    public void clear() {
        pendingTasks.clear();
    }

    public int pendingCount() {
        return pendingTasks.size();
    }
}
