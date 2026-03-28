package com.example.examplemod.nation.model;

import net.minecraft.nbt.CompoundTag;

import java.util.Locale;

public record NationWarRecord(
        String warId,
        String attackerNationId,
        String defenderNationId,
        String state,
        int attackerScore,
        int defenderScore,
        double captureProgress,
        long startedAt,
        long lastCaptureTick,
        String winnerNationId,
        long endedAt,
        String captureState
) {
    public NationWarRecord {
        warId = normalize(warId);
        attackerNationId = normalize(attackerNationId);
        defenderNationId = normalize(defenderNationId);
        state = normalize(state);
        winnerNationId = normalize(winnerNationId);
        captureState = normalize(captureState);
        attackerScore = Math.max(0, attackerScore);
        defenderScore = Math.max(0, defenderScore);
        captureProgress = Math.max(0.0D, captureProgress);
        startedAt = Math.max(0L, startedAt);
        lastCaptureTick = Math.max(0L, lastCaptureTick);
        endedAt = Math.max(0L, endedAt);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("WarId", warId);
        tag.putString("AttackerNationId", attackerNationId);
        tag.putString("DefenderNationId", defenderNationId);
        tag.putString("State", state);
        tag.putInt("AttackerScore", attackerScore);
        tag.putInt("DefenderScore", defenderScore);
        tag.putDouble("CaptureProgress", captureProgress);
        tag.putLong("StartedAt", startedAt);
        tag.putLong("LastCaptureTick", lastCaptureTick);
        tag.putString("WinnerNationId", winnerNationId);
        tag.putLong("EndedAt", endedAt);
        tag.putString("CaptureState", captureState);
        return tag;
    }

    public static NationWarRecord load(CompoundTag tag) {
        return new NationWarRecord(
                tag.getString("WarId"),
                tag.getString("AttackerNationId"),
                tag.getString("DefenderNationId"),
                tag.getString("State"),
                tag.getInt("AttackerScore"),
                tag.getInt("DefenderScore"),
                tag.getDouble("CaptureProgress"),
                tag.getLong("StartedAt"),
                tag.getLong("LastCaptureTick"),
                tag.getString("WinnerNationId"),
                tag.contains("EndedAt") ? tag.getLong("EndedAt") : 0L,
                tag.contains("CaptureState") ? tag.getString("CaptureState") : "idle"
        );
    }

    public boolean isActive() {
        return "active".equals(state);
    }

    public boolean isEnded() {
        return "ended".equals(state);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
