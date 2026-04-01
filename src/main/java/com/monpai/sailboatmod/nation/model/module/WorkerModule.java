package com.monpai.sailboatmod.nation.model.module;

import com.monpai.sailboatmod.nation.model.IBuildingModule;
import com.monpai.sailboatmod.nation.model.PlacedStructureRecord;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages worker assignment to a building
 */
public class WorkerModule implements IBuildingModule {
    private final int maxWorkers;
    private final List<String> assignedWorkers = new ArrayList<>();

    public WorkerModule(int maxWorkers) {
        this.maxWorkers = maxWorkers;
    }

    @Override
    public String getId() { return "worker"; }

    public boolean assignWorker(String residentId) {
        if (assignedWorkers.size() >= maxWorkers || assignedWorkers.contains(residentId)) return false;
        assignedWorkers.add(residentId);
        return true;
    }

    public void removeWorker(String residentId) {
        assignedWorkers.remove(residentId);
    }

    public List<String> getAssignedWorkers() { return Collections.unmodifiableList(assignedWorkers); }
    public int getMaxWorkers() { return maxWorkers; }
    public boolean isFull() { return assignedWorkers.size() >= maxWorkers; }

    @Override
    public void onDemolished(PlacedStructureRecord building) {
        assignedWorkers.clear();
    }

    @Override
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Max", maxWorkers);
        CompoundTag workers = new CompoundTag();
        for (int i = 0; i < assignedWorkers.size(); i++) {
            workers.putString("W" + i, assignedWorkers.get(i));
        }
        workers.putInt("Count", assignedWorkers.size());
        tag.put("Workers", workers);
        return tag;
    }

    @Override
    public void load(CompoundTag tag) {
        assignedWorkers.clear();
        if (tag.contains("Workers")) {
            CompoundTag workers = tag.getCompound("Workers");
            int count = workers.getInt("Count");
            for (int i = 0; i < count; i++) {
                assignedWorkers.add(workers.getString("W" + i));
            }
        }
    }
}
