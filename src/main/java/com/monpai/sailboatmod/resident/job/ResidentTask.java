package com.monpai.sailboatmod.resident.job;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * A single unit of work a resident can perform.
 */
public record ResidentTask(
        String taskId,
        String displayName,
        int durationTicks,
        List<ItemStack> inputs,
        List<ItemStack> outputs
) {
    public ResidentTask {
        taskId = taskId == null ? "" : taskId.trim();
        displayName = displayName == null ? taskId : displayName.trim();
        durationTicks = Math.max(100, durationTicks);
        if (inputs == null) inputs = List.of();
        if (outputs == null) outputs = List.of();
    }
}
