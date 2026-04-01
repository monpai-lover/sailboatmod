package com.monpai.sailboatmod.resident.model;

import net.minecraft.nbt.CompoundTag;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Simplified disease system (inspired by MineColonies ICitizenDiseaseHandler)
 */
public class DiseaseData {
    public enum Disease {
        NONE("none", 0),
        COLD("cold", 600),         // 30 seconds
        PLAGUE("plague", 2400),    // 2 minutes
        FOOD_POISONING("food_poisoning", 1200); // 1 minute

        private final String id;
        private final int duration;

        Disease(String id, int duration) { this.id = id; this.duration = duration; }
        public String id() { return id; }
        public int duration() { return duration; }

        public static Disease fromId(String id) {
            for (Disease d : values()) if (d.id.equals(id)) return d;
            return NONE;
        }
    }

    private Disease currentDisease = Disease.NONE;
    private int remainingTicks = 0;
    private int immunity = 50; // 0-100, higher = less likely to get sick

    public boolean isSick() { return currentDisease != Disease.NONE; }
    public Disease getCurrentDisease() { return currentDisease; }
    public int getRemainingTicks() { return remainingTicks; }
    public int getImmunity() { return immunity; }

    /**
     * Try to infect the resident. Higher immunity = lower chance.
     */
    public boolean tryInfect(Disease disease) {
        if (isSick()) return false;
        int chance = 100 - immunity;
        if (ThreadLocalRandom.current().nextInt(100) < chance) {
            currentDisease = disease;
            remainingTicks = disease.duration();
            return true;
        }
        return false;
    }

    /**
     * Tick the disease. Returns true if disease just ended.
     */
    public boolean tick() {
        if (!isSick()) return false;
        remainingTicks--;
        if (remainingTicks <= 0) {
            currentDisease = Disease.NONE;
            immunity = Math.min(100, immunity + 5); // Build immunity after recovery
            return true;
        }
        return false;
    }

    /**
     * Cure the disease immediately.
     */
    public void cure() {
        currentDisease = Disease.NONE;
        remainingTicks = 0;
    }

    /**
     * Good food increases immunity.
     */
    public void onEat(boolean goodFood) {
        if (goodFood) {
            immunity = Math.min(100, immunity + 2);
        }
    }

    /**
     * Random chance to get sick based on conditions.
     */
    public void processRandomSickness(int hunger, boolean hasHome) {
        if (isSick()) return;
        int riskFactor = 0;
        if (hunger < 20) riskFactor += 30;
        if (!hasHome) riskFactor += 20;

        if (ThreadLocalRandom.current().nextInt(1000) < riskFactor) {
            Disease[] diseases = {Disease.COLD, Disease.FOOD_POISONING};
            tryInfect(diseases[ThreadLocalRandom.current().nextInt(diseases.length)]);
        }
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Disease", currentDisease.id());
        tag.putInt("Remaining", remainingTicks);
        tag.putInt("Immunity", immunity);
        return tag;
    }

    public static DiseaseData load(CompoundTag tag) {
        DiseaseData data = new DiseaseData();
        data.currentDisease = Disease.fromId(tag.getString("Disease"));
        data.remainingTicks = tag.getInt("Remaining");
        data.immunity = tag.contains("Immunity") ? tag.getInt("Immunity") : 50;
        return data;
    }
}
