package com.monpai.sailboatmod.nation.model;

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
        String containerAccessLevel,
        String redstoneAccessLevel,
        String entityUseAccessLevel,
        String entityDamageAccessLevel,
        long claimedAt
) {
    public NationClaimRecord {
        dimensionId = normalize(dimensionId);
        nationId = normalize(nationId);
        townId = normalize(townId);
        breakAccessLevel = normalizeAccessLevel(breakAccessLevel);
        placeAccessLevel = normalizeAccessLevel(placeAccessLevel);
        useAccessLevel = normalizeAccessLevel(useAccessLevel);
        containerAccessLevel = normalizeAccessLevel(containerAccessLevel);
        redstoneAccessLevel = normalizeAccessLevel(redstoneAccessLevel);
        entityUseAccessLevel = normalizeAccessLevel(entityUseAccessLevel);
        entityDamageAccessLevel = normalizeAccessLevel(entityDamageAccessLevel);
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
        tag.putString("ContainerAccessLevel", containerAccessLevel);
        tag.putString("RedstoneAccessLevel", redstoneAccessLevel);
        tag.putString("EntityUseAccessLevel", entityUseAccessLevel);
        tag.putString("EntityDamageAccessLevel", entityDamageAccessLevel);
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
                tag.contains("ContainerAccessLevel") ? tag.getString("ContainerAccessLevel") : NationClaimAccessLevel.MEMBER.id(),
                tag.contains("RedstoneAccessLevel") ? tag.getString("RedstoneAccessLevel") : NationClaimAccessLevel.MEMBER.id(),
                tag.contains("EntityUseAccessLevel") ? tag.getString("EntityUseAccessLevel") : NationClaimAccessLevel.MEMBER.id(),
                tag.contains("EntityDamageAccessLevel") ? tag.getString("EntityDamageAccessLevel") : NationClaimAccessLevel.MEMBER.id(),
                tag.getLong("ClaimedAt")
        );
    }

    public NationClaimRecord withPermissions(NationClaimAccessLevel breakLevel, NationClaimAccessLevel placeLevel, NationClaimAccessLevel useLevel) {
        return new NationClaimRecord(
                dimensionId, chunkX, chunkZ, nationId, townId,
                breakLevel == null ? breakAccessLevel : breakLevel.id(),
                placeLevel == null ? placeAccessLevel : placeLevel.id(),
                useLevel == null ? useAccessLevel : useLevel.id(),
                containerAccessLevel, redstoneAccessLevel, entityUseAccessLevel, entityDamageAccessLevel,
                claimedAt
        );
    }

    public NationClaimRecord withPermission(String actionId, NationClaimAccessLevel level) {
        if (actionId == null || level == null) return this;
        String levelId = level.id();
        return switch (actionId.toLowerCase(Locale.ROOT)) {
            case "break" -> new NationClaimRecord(dimensionId, chunkX, chunkZ, nationId, townId, levelId, placeAccessLevel, useAccessLevel, containerAccessLevel, redstoneAccessLevel, entityUseAccessLevel, entityDamageAccessLevel, claimedAt);
            case "place" -> new NationClaimRecord(dimensionId, chunkX, chunkZ, nationId, townId, breakAccessLevel, levelId, useAccessLevel, containerAccessLevel, redstoneAccessLevel, entityUseAccessLevel, entityDamageAccessLevel, claimedAt);
            case "use" -> new NationClaimRecord(dimensionId, chunkX, chunkZ, nationId, townId, breakAccessLevel, placeAccessLevel, levelId, containerAccessLevel, redstoneAccessLevel, entityUseAccessLevel, entityDamageAccessLevel, claimedAt);
            case "container" -> new NationClaimRecord(dimensionId, chunkX, chunkZ, nationId, townId, breakAccessLevel, placeAccessLevel, useAccessLevel, levelId, redstoneAccessLevel, entityUseAccessLevel, entityDamageAccessLevel, claimedAt);
            case "redstone" -> new NationClaimRecord(dimensionId, chunkX, chunkZ, nationId, townId, breakAccessLevel, placeAccessLevel, useAccessLevel, containerAccessLevel, levelId, entityUseAccessLevel, entityDamageAccessLevel, claimedAt);
            case "entity_use" -> new NationClaimRecord(dimensionId, chunkX, chunkZ, nationId, townId, breakAccessLevel, placeAccessLevel, useAccessLevel, containerAccessLevel, redstoneAccessLevel, levelId, entityDamageAccessLevel, claimedAt);
            case "entity_damage" -> new NationClaimRecord(dimensionId, chunkX, chunkZ, nationId, townId, breakAccessLevel, placeAccessLevel, useAccessLevel, containerAccessLevel, redstoneAccessLevel, entityUseAccessLevel, levelId, claimedAt);
            default -> this;
        };
    }

    public String accessLevelFor(String actionId) {
        if (actionId == null) return NationClaimAccessLevel.MEMBER.id();
        return switch (actionId.toLowerCase(Locale.ROOT)) {
            case "break" -> breakAccessLevel;
            case "place" -> placeAccessLevel;
            case "use" -> useAccessLevel;
            case "container" -> containerAccessLevel;
            case "redstone" -> redstoneAccessLevel;
            case "entity_use" -> entityUseAccessLevel;
            case "entity_damage" -> entityDamageAccessLevel;
            default -> NationClaimAccessLevel.MEMBER.id();
        };
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeAccessLevel(String value) {
        NationClaimAccessLevel accessLevel = NationClaimAccessLevel.fromId(value);
        return accessLevel == null ? NationClaimAccessLevel.MEMBER.id() : accessLevel.id();
    }
}
