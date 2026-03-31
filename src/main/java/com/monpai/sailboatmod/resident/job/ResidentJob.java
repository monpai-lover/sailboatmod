package com.monpai.sailboatmod.resident.job;

import com.monpai.sailboatmod.resident.model.Profession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * A job definition: ties a profession to a set of tasks it can perform.
 */
public record ResidentJob(
        Profession profession,
        String jobName,
        List<ResidentTask> tasks
) {
    public ResidentJob {
        if (profession == null) profession = Profession.FARMER;
        jobName = jobName == null ? profession.displayName() : jobName.trim();
        if (tasks == null) tasks = List.of();
    }
}
