package com.monpai.sailboatmod.nation.service;

import net.minecraft.server.MinecraftServer;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Stub class - road system refactored. Pending integration with new road package.
 */
public final class RoadPlanningTaskService {
    private RoadPlanningTaskService() {}

    public record TaskKey(String category, String id) {}

    public static RoadPlanningTaskService get() {
        return null;
    }

    public static void onServerStarted(MinecraftServer server) {}

    public static void onServerStopping() {}

    public static void throwIfCancelled() {}

    public <T> CompletableFuture<T> submitLatest(TaskKey key, Supplier<T> task, Consumer<T> callback) {
        T result = task.get();
        if (callback != null) callback.accept(result);
        return CompletableFuture.completedFuture(result);
    }
}
