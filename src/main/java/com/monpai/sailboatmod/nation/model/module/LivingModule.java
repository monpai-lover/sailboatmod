package com.monpai.sailboatmod.nation.model.module;

import com.monpai.sailboatmod.nation.model.IBuildingModule;
import com.monpai.sailboatmod.nation.model.PlacedStructureRecord;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages resident housing in a building
 */
public class LivingModule implements IBuildingModule {
    private final int maxResidents;
    private final List<String> assignedResidents = new ArrayList<>();

    public LivingModule(int maxResidents) {
        this.maxResidents = maxResidents;
    }

    @Override
    public String getId() { return "living"; }

    public boolean assignResident(String residentId) {
        if (assignedResidents.size() >= maxResidents || assignedResidents.contains(residentId)) return false;
        assignedResidents.add(residentId);
        return true;
    }

    public void removeResident(String residentId) {
        assignedResidents.remove(residentId);
    }

    public List<String> getAssignedResidents() { return Collections.unmodifiableList(assignedResidents); }
    public int getMaxResidents() { return maxResidents; }
    public int getAvailableSlots() { return maxResidents - assignedResidents.size(); }

    @Override
    public void onUpgraded(PlacedStructureRecord building, int newLevel) {
        // Capacity increases handled by BuildingStats
    }

    @Override
    public void onDemolished(PlacedStructureRecord building) {
        assignedResidents.clear();
    }

    @Override
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Max", maxResidents);
        CompoundTag residents = new CompoundTag();
        for (int i = 0; i < assignedResidents.size(); i++) {
            residents.putString("R" + i, assignedResidents.get(i));
        }
        residents.putInt("Count", assignedResidents.size());
        tag.put("Residents", residents);
        return tag;
    }

    @Override
    public void load(CompoundTag tag) {
        assignedResidents.clear();
        if (tag.contains("Residents")) {
            CompoundTag residents = tag.getCompound("Residents");
            int count = residents.getInt("Count");
            for (int i = 0; i < count; i++) {
                assignedResidents.add(residents.getString("R" + i));
            }
        }
    }
}
