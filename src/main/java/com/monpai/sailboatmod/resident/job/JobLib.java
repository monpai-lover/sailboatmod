package com.monpai.sailboatmod.resident.job;

import com.monpai.sailboatmod.resident.model.Profession;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Static registry of jobs per profession.
 */
public final class JobLib {
    private static final Map<Profession, ResidentJob> JOBS = new LinkedHashMap<>();

    public static final ResidentJob FARMER_JOB = register(new ResidentJob(
            Profession.FARMER, "Farmer",
            List.of(TaskLib.HARVEST_WHEAT, TaskLib.HARVEST_CARROT, TaskLib.HARVEST_POTATO)));

    public static final ResidentJob MINER_JOB = register(new ResidentJob(
            Profession.MINER, "Miner",
            List.of(TaskLib.MINE_STONE, TaskLib.MINE_IRON, TaskLib.MINE_COAL)));

    public static final ResidentJob LUMBERJACK_JOB = register(new ResidentJob(
            Profession.LUMBERJACK, "Lumberjack",
            List.of(TaskLib.CHOP_OAK, TaskLib.CHOP_SPRUCE)));

    public static final ResidentJob FISHERMAN_JOB = register(new ResidentJob(
            Profession.FISHERMAN, "Fisherman",
            List.of(TaskLib.CATCH_COD, TaskLib.CATCH_SALMON)));

    public static final ResidentJob BLACKSMITH_JOB = register(new ResidentJob(
            Profession.BLACKSMITH, "Blacksmith",
            List.of(TaskLib.SMELT_IRON, TaskLib.FORGE_SWORD, TaskLib.FORGE_SHIELD)));

    public static final ResidentJob BAKER_JOB = register(new ResidentJob(
            Profession.BAKER, "Baker",
            List.of(TaskLib.BAKE_BREAD)));

    public static final ResidentJob GUARD_JOB = register(new ResidentJob(
            Profession.GUARD, "Guard", List.of()));

    public static final ResidentJob SOLDIER_JOB = register(new ResidentJob(
            Profession.SOLDIER, "Soldier", List.of()));

    private static ResidentJob register(ResidentJob job) {
        JOBS.put(job.profession(), job);
        return job;
    }

    public static ResidentJob getJob(Profession profession) {
        return JOBS.getOrDefault(profession, FARMER_JOB);
    }

    public static Map<Profession, ResidentJob> all() {
        return Map.copyOf(JOBS);
    }

    private JobLib() {}
}
