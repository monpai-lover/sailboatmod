package com.monpai.sailboatmod.road.generation;

import com.monpai.sailboatmod.road.config.RoadConfig;
import com.monpai.sailboatmod.road.model.StructureConnection;
import com.monpai.sailboatmod.road.pathfinding.*;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.server.level.ServerLevel;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

public class RoadGenerationService {
    private final RoadConfig config;
    private final ThreadPoolManager threadPool;
    private final Queue<RoadGenerationTask> pendingQueue = new ConcurrentLinkedQueue<>();
    private final Map<String, RoadGenerationTask> activeTasks = new LinkedHashMap<>();
    private final List<RoadGenerationTask> completedTasks = new ArrayList<>();
    private int taskCounter = 0;

    public RoadGenerationService(RoadConfig config) {
        this.config = config;
        this.threadPool = new ThreadPoolManager(config.getPathfinding().getThreadPoolSize());
    }

    public String submit(StructureConnection connection) {
        String taskId = "road-gen-" + (taskCounter++);
        RoadGenerationTask task = new RoadGenerationTask(taskId, connection);
        pendingQueue.add(task);
        return taskId;
    }

    public void tick(ServerLevel level) {
        while (!pendingQueue.isEmpty()) {
            RoadGenerationTask task = pendingQueue.poll();
            if (task == null) break;
            task.setStatus(GenerationStatus.GENERATING);
            activeTasks.put(task.getTaskId(), task);

            Pathfinder pathfinder = PathfinderFactory.create(config.getPathfinding());
            TerrainSamplingCache cache = new TerrainSamplingCache(level, config.getPathfinding().getSamplingPrecision());

            CompletableFuture<PathResult> future = CompletableFuture.supplyAsync(() ->
                pathfinder.findPath(
                    task.getConnection().from(),
                    task.getConnection().to(),
                    cache
                ), threadPool.getExecutor()
            );
            task.setFuture(future);
        }

        Iterator<Map.Entry<String, RoadGenerationTask>> it = activeTasks.entrySet().iterator();
        while (it.hasNext()) {
            RoadGenerationTask task = it.next().getValue();
            CompletableFuture<PathResult> future = task.getFuture();
            if (future != null && future.isDone()) {
                try {
                    PathResult result = future.get();
                    task.setResult(result);
                    task.setStatus(result.success() ? GenerationStatus.COMPLETED : GenerationStatus.FAILED);
                } catch (Exception e) {
                    task.setResult(PathResult.failure(e.getMessage()));
                    task.setStatus(GenerationStatus.FAILED);
                }
                completedTasks.add(task);
                it.remove();
            }
        }
    }

    public List<RoadGenerationTask> pollCompleted() {
        List<RoadGenerationTask> result = new ArrayList<>(completedTasks);
        completedTasks.clear();
        return result;
    }

    public Optional<RoadGenerationTask> getTask(String taskId) {
        RoadGenerationTask task = activeTasks.get(taskId);
        if (task != null) return Optional.of(task);
        return completedTasks.stream().filter(t -> t.getTaskId().equals(taskId)).findFirst();
    }

    public void cancelTask(String taskId) {
        RoadGenerationTask task = activeTasks.remove(taskId);
        if (task != null && task.getFuture() != null) {
            task.getFuture().cancel(true);
            task.setStatus(GenerationStatus.FAILED);
        }
    }

    public void shutdown() {
        threadPool.shutdown();
    }
}