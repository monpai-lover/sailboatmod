package com.monpai.sailboatmod.nation.service;

import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;

/** Hostile automatic spawns only. Does not delete existing entities or override vanilla spawning rules. */
public final class ClaimMobSpawnPolicy {
    private ClaimMobSpawnPolicy() { }
    public static boolean automaticMonster(MobCategory category, MobSpawnType type) {
        if (category != MobCategory.MONSTER || type == null) return false;
        return switch (type) {
            case NATURAL, CHUNK_GENERATION, SPAWNER, TRIAL_SPAWNER, STRUCTURE,
                    PATROL, REINFORCEMENT, EVENT, JOCKEY, MOB_SUMMONED, TRIGGERED -> true;
            default -> false; // commands, eggs, buckets, conversions and breeding are not automatic spawn control
        };
    }
}
