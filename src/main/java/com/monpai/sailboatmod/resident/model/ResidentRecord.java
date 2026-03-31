package com.monpai.sailboatmod.resident.model;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public record ResidentRecord(
        String residentId,
        String townId,
        String name,
        String skinHash,
        Profession profession,
        Gender gender,
        int age,
        int hunger,
        boolean educated,
        Culture culture,
        int level,
        int happiness,
        BlockPos assignedWorkplace,
        BlockPos assignedBed,
        long createdAt
) {
    public static final int MAX_HUNGER = 100;
    public static final float EDUCATION_MULTIPLIER = 1.5f;

    public ResidentRecord {
        residentId = residentId == null ? "" : residentId.trim();
        townId = townId == null ? "" : townId.trim().toLowerCase(Locale.ROOT);
        name = name == null ? "Villager" : name.trim();
        skinHash = skinHash == null ? "" : skinHash.trim();
        if (profession == null) profession = Profession.UNEMPLOYED;
        if (gender == null) gender = Gender.MALE;
        age = Math.max(18, Math.min(80, age));
        hunger = Math.max(0, Math.min(MAX_HUNGER, hunger));
        if (culture == null) culture = Culture.EUROPEAN;
        level = Math.max(1, Math.min(5, level));
        happiness = Math.max(0, Math.min(100, happiness));
        if (assignedWorkplace == null) assignedWorkplace = BlockPos.ZERO;
        if (assignedBed == null) assignedBed = BlockPos.ZERO;
    }

    public static ResidentRecord create(String townId, String name, String skinHash, Culture culture) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return new ResidentRecord(
                UUID.randomUUID().toString().substring(0, 8),
                townId, name, skinHash,
                Profession.UNEMPLOYED,
                Gender.random(),
                r.nextInt(18, 45),
                MAX_HUNGER,
                false,
                culture,
                1, 50,
                BlockPos.ZERO, BlockPos.ZERO,
                System.currentTimeMillis()
        );
    }

    public float productionMultiplier() {
        return educated ? EDUCATION_MULTIPLIER : 1.0f;
    }

    public boolean isHungry() { return hunger < 20; }
    public boolean isStarving() { return hunger <= 0; }

    public ResidentRecord withProfession(Profession p) {
        return new ResidentRecord(residentId, townId, name, skinHash, p, gender, age, hunger, educated, culture, level, happiness, assignedWorkplace, assignedBed, createdAt);
    }
    public ResidentRecord withHunger(int h) {
        return new ResidentRecord(residentId, townId, name, skinHash, profession, gender, age, h, educated, culture, level, happiness, assignedWorkplace, assignedBed, createdAt);
    }
    public ResidentRecord withEducated(boolean e) {
        return new ResidentRecord(residentId, townId, name, skinHash, profession, gender, age, hunger, e, culture, level, happiness, assignedWorkplace, assignedBed, createdAt);
    }
    public ResidentRecord withLevel(int l) {
        return new ResidentRecord(residentId, townId, name, skinHash, profession, gender, age, hunger, educated, culture, l, happiness, assignedWorkplace, assignedBed, createdAt);
    }
    public ResidentRecord withHappiness(int h) {
        return new ResidentRecord(residentId, townId, name, skinHash, profession, gender, age, hunger, educated, culture, level, h, assignedWorkplace, assignedBed, createdAt);
    }
    public ResidentRecord withWorkplace(BlockPos pos) {
        return new ResidentRecord(residentId, townId, name, skinHash, profession, gender, age, hunger, educated, culture, level, happiness, pos, assignedBed, createdAt);
    }
    public ResidentRecord withBed(BlockPos pos) {
        return new ResidentRecord(residentId, townId, name, skinHash, profession, gender, age, hunger, educated, culture, level, happiness, assignedWorkplace, pos, createdAt);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", residentId);
        tag.putString("Town", townId);
        tag.putString("Name", name);
        tag.putString("Skin", skinHash);
        tag.putString("Profession", profession.id());
        tag.putString("Gender", gender.id());
        tag.putInt("Age", age);
        tag.putInt("Hunger", hunger);
        tag.putBoolean("Educated", educated);
        tag.putString("Culture", culture.id());
        tag.putInt("Level", level);
        tag.putInt("Happiness", happiness);
        tag.putLong("WorkX", assignedWorkplace.getX());
        tag.putLong("WorkY", assignedWorkplace.getY());
        tag.putLong("WorkZ", assignedWorkplace.getZ());
        tag.putLong("BedX", assignedBed.getX());
        tag.putLong("BedY", assignedBed.getY());
        tag.putLong("BedZ", assignedBed.getZ());
        tag.putLong("CreatedAt", createdAt);
        return tag;
    }

    public static ResidentRecord load(CompoundTag tag) {
        return new ResidentRecord(
                tag.getString("Id"),
                tag.getString("Town"),
                tag.getString("Name"),
                tag.getString("Skin"),
                Profession.fromId(tag.getString("Profession")),
                Gender.fromId(tag.getString("Gender")),
                tag.contains("Age") ? tag.getInt("Age") : 25,
                tag.contains("Hunger") ? tag.getInt("Hunger") : MAX_HUNGER,
                tag.getBoolean("Educated"),
                Culture.fromId(tag.getString("Culture")),
                tag.getInt("Level"),
                tag.getInt("Happiness"),
                new BlockPos((int) tag.getLong("WorkX"), (int) tag.getLong("WorkY"), (int) tag.getLong("WorkZ")),
                new BlockPos((int) tag.getLong("BedX"), (int) tag.getLong("BedY"), (int) tag.getLong("BedZ")),
                tag.getLong("CreatedAt")
        );
    }
}
