package com.monpai.sailboatmod.resident.job;

import com.monpai.sailboatmod.resident.model.Profession;
import net.minecraft.world.item.ItemStack;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Tracks a single resident's current task progress.
 */
public class TaskManager {
    private final String residentId;
    private ResidentTask currentTask;
    private int ticksWorked;

    public TaskManager(String residentId) {
        this.residentId = residentId;
    }

    public void assignRandomTask(Profession profession) {
        ResidentJob job = JobLib.getJob(profession);
        if (job.tasks().isEmpty()) {
            currentTask = null;
            return;
        }
        currentTask = job.tasks().get(ThreadLocalRandom.current().nextInt(job.tasks().size()));
        ticksWorked = 0;
    }

    /**
     * Tick the task. Returns completed task outputs if done, null otherwise.
     */
    public java.util.List<ItemStack> tick() {
        if (currentTask == null) return null;
        ticksWorked++;
        if (ticksWorked >= currentTask.durationTicks()) {
            java.util.List<ItemStack> outputs = currentTask.outputs().stream()
                    .map(ItemStack::copy).toList();
            currentTask = null;
            ticksWorked = 0;
            return outputs;
        }
        return null;
    }

    public boolean hasTask() { return currentTask != null; }
    public ResidentTask currentTask() { return currentTask; }
    public int ticksWorked() { return ticksWorked; }
    public float progress() {
        if (currentTask == null) return 0f;
        return (float) ticksWorked / currentTask.durationTicks();
    }
    public String residentId() { return residentId; }
}
