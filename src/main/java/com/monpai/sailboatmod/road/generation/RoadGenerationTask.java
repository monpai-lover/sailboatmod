package com.monpai.sailboatmod.road.generation;

import com.monpai.sailboatmod.road.model.StructureConnection;
import com.monpai.sailboatmod.road.pathfinding.PathResult;

import java.util.concurrent.CompletableFuture;

public class RoadGenerationTask {
    private final String taskId;
    private final StructureConnection connection;
    private volatile GenerationStatus status;
    private volatile PathResult result;
    private volatile CompletableFuture<PathResult> future;

    public RoadGenerationTask(String taskId, StructureConnection connection) {
        this.taskId = taskId;
        this.connection = connection;
        this.status = GenerationStatus.PLANNED;
    }

    public String getTaskId() { return taskId; }
    public StructureConnection getConnection() { return connection; }
    public GenerationStatus getStatus() { return status; }
    public void setStatus(GenerationStatus status) { this.status = status; }
    public PathResult getResult() { return result; }
    public void setResult(PathResult result) { this.result = result; }
    public CompletableFuture<PathResult> getFuture() { return future; }
    public void setFuture(CompletableFuture<PathResult> future) { this.future = future; }
}
