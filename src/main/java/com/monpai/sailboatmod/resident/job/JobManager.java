package com.monpai.sailboatmod.resident.job;

import com.monpai.sailboatmod.resident.data.ResidentSavedData;
import com.monpai.sailboatmod.resident.model.Profession;
import com.monpai.sailboatmod.resident.model.ResidentRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side manager that ticks all resident task managers and deposits outputs.
 * One instance per server, keyed by residentId.
 */
public final class JobManager {
    private static final Map<String, TaskManager> ACTIVE = new LinkedHashMap<>();
    private static final int TAX_PERCENT = 20; // 20% of output goes to town treasury

    public static void ensureTask(String residentId, Profession profession) {
        TaskManager tm = ACTIVE.computeIfAbsent(residentId, TaskManager::new);
        if (!tm.hasTask()) {
            tm.assignRandomTask(profession);
        }
    }

    public static TaskManager getTaskManager(String residentId) {
        return ACTIVE.get(residentId);
    }

    /**
     * Tick a resident's task. Returns produced items (after tax) to deposit into warehouse.
     * Tax portion should be deposited into town treasury by the caller.
     */
    public static TickResult tick(String residentId, Profession profession) {
        ensureTask(residentId, profession);
        TaskManager tm = ACTIVE.get(residentId);
        if (tm == null) return null;
        List<ItemStack> outputs = tm.tick();
        if (outputs == null || outputs.isEmpty()) return null;

        // Split outputs: tax portion vs resident portion
        List<ItemStack> forWarehouse = new ArrayList<>();
        List<ItemStack> forTreasury = new ArrayList<>();
        for (ItemStack stack : outputs) {
            if (stack.isEmpty()) continue;
            int taxCount = Math.max(1, stack.getCount() * TAX_PERCENT / 100);
            int keepCount = stack.getCount() - taxCount;
            if (keepCount > 0) {
                ItemStack keep = stack.copy();
                keep.setCount(keepCount);
                forWarehouse.add(keep);
            }
            ItemStack tax = stack.copy();
            tax.setCount(taxCount);
            forTreasury.add(tax);
        }

        // Auto-assign next task
        tm.assignRandomTask(profession);
        return new TickResult(forWarehouse, forTreasury);
    }

    public static void remove(String residentId) {
        ACTIVE.remove(residentId);
    }

    public static void clearAll() {
        ACTIVE.clear();
    }

    public record TickResult(List<ItemStack> forWarehouse, List<ItemStack> forTreasury) {}

    private JobManager() {}
}
