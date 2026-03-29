package com.example.examplemod.nation.model;

import net.minecraft.nbt.CompoundTag;

import java.util.Locale;

public record NationClaimRecord(
        String dimensionId,
        int chunkX,
        int chunkZ,
        String nationId,
        String townId,
        String breakAccessLevel,
        String placeAccessLevel,
        String useAccessLevel,
        long claimedAt
) {
    public NationClaimRecord {
        dimensionId = normalize(dimensionId);
        nationId = normalize(nationId);
        townId = normalize(townId);
        breakAccessLevel = normalizeAccessLevel(breakAccessLevel);
        placeAccessLevel = normalizeAccessLevel(placeAccessLevel);
        useAccessLevel = normalizeAccessLevel(useAccessLevel);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("DimensionId", dimensionId);
        tag.putInt("ChunkX", chunkX);
        tag.putInt("ChunkZ", chunkZ);
        tag.putString("NationId", nationId);
        tag.putString("TownId", townId);
        tag.putString("BreakAccessLevel", breakAccessLevel);
        tag.putString("PlaceAccessLevel", placeAccessLevel);
        tag.putString("UseAccessLevel", useAccessLevel);
        tag.putLong("ClaimedAt", claimedAt);
        return tag;
    }

    public static NationClaimRecord load(CompoundTag tag) {
        return new NationClaimRecord(
                tag.getString("DimensionId"),
                tag.getInt("ChunkX"),
                tag.getInt("ChunkZ"),
                tag.getString("NationId"),
                tag.contains("TownId") ? tag.getString("TownId") : "",
                tag.contains("BreakAccessLevel") ? tag.getString("BreakAccessLevel") : NationClaimAccessLevel.MEMBER.id(),
                tag.contains("PlaceAccessLevel") ? tag.getString("PlaceAccessLevel") : NationClaimAccessLevel.MEMBER.id(),
                tag.contains("UseAccessLevel") ? tag.getString("UseAccessLevel") : NationClaimAccessLevel.MEMBER.id(),
                tag.getLong("ClaimedAt")
        );
    }

    public NationClaimRecord withPermissions(NationClaimAccessLevel breakLevel, NationClaimAccessLevel placeLevel, NationClaimAccessLevel useLevel) {
        return new NationClaimRecord(
                dimensionId,
                chunkX,
                chunkZ,
                nationId,
                townId,
                breakLevel == null ? breakAccessLevel : breakLevel.id(),
                placeLevel == null ? placeAccessLevel : placeLevel.id(),
                useLevel == null ? useAccessLevel : useLevel.id(),
                claimedAt
        );
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeAccessLevel(String value) {
        NationClaimAccessLevel accessLevel = NationClaimAccessLevel.fromId(value);
        return accessLevel == null ? NationClaimAccessLevel.MEMBER.id() : accessLevel.id();
    }
}
