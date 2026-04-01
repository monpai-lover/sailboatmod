package com.monpai.sailboatmod.resident.model;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.*;

/**
 * Multi-factor happiness system (inspired by MineColonies ICitizenHappinessHandler)
 */
public class HappinessData {
    private final Map<String, HappinessModifier> modifiers = new LinkedHashMap<>();

    // Built-in modifier keys
    public static final String FOOD = "food";
    public static final String HOUSING = "housing";
    public static final String SAFETY = "safety";
    public static final String EMPLOYMENT = "employment";
    public static final String SOCIAL = "social";

    public HappinessData() {
        // Initialize with neutral defaults
        modifiers.put(FOOD, new HappinessModifier(FOOD, 0, 1.2));
        modifiers.put(HOUSING, new HappinessModifier(HOUSING, 0, 1.0));
        modifiers.put(SAFETY, new HappinessModifier(SAFETY, 0, 1.5));
        modifiers.put(EMPLOYMENT, new HappinessModifier(EMPLOYMENT, 0, 0.8));
        modifiers.put(SOCIAL, new HappinessModifier(SOCIAL, 0, 0.5));
    }

    public void setModifier(String key, double value) {
        HappinessModifier existing = modifiers.get(key);
        double weight = existing != null ? existing.weight() : 1.0;
        modifiers.put(key, new HappinessModifier(key, Math.max(-50, Math.min(50, value)), weight));
    }

    public void addModifier(String key, double value, double weight) {
        modifiers.put(key, new HappinessModifier(key, Math.max(-50, Math.min(50, value)), weight));
    }

    public Collection<HappinessModifier> getModifiers() {
        return Collections.unmodifiableCollection(modifiers.values());
    }

    /**
     * Calculate total happiness (0-100)
     */
    public int calculateHappiness() {
        double base = 50.0;
        double totalWeight = 0;
        double weightedSum = 0;

        for (HappinessModifier mod : modifiers.values()) {
            weightedSum += mod.value() * mod.weight();
            totalWeight += mod.weight();
        }

        if (totalWeight > 0) {
            base += weightedSum / totalWeight;
        }

        return (int) Math.max(0, Math.min(100, base));
    }

    /**
     * Process daily happiness update based on resident state
     */
    public void processDailyUpdate(ResidentRecord record) {
        // Food factor
        double foodMod = record.hunger() > 80 ? 20 : record.hunger() > 40 ? 0 : -20;
        setModifier(FOOD, foodMod);

        // Housing factor
        boolean hasHome = record.assignedBed() != null && !record.assignedBed().equals(net.minecraft.core.BlockPos.ZERO);
        setModifier(HOUSING, hasHome ? 15 : -25);

        // Employment factor
        boolean hasJob = record.profession() != Profession.UNEMPLOYED;
        setModifier(EMPLOYMENT, hasJob ? 10 : -15);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (HappinessModifier mod : modifiers.values()) {
            CompoundTag modTag = new CompoundTag();
            modTag.putString("Key", mod.key());
            modTag.putDouble("Value", mod.value());
            modTag.putDouble("Weight", mod.weight());
            list.add(modTag);
        }
        tag.put("Modifiers", list);
        return tag;
    }

    public static HappinessData load(CompoundTag tag) {
        HappinessData data = new HappinessData();
        if (tag.contains("Modifiers")) {
            ListTag list = tag.getList("Modifiers", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag modTag = list.getCompound(i);
                data.addModifier(modTag.getString("Key"), modTag.getDouble("Value"), modTag.getDouble("Weight"));
            }
        }
        return data;
    }

    public record HappinessModifier(String key, double value, double weight) {}
}
