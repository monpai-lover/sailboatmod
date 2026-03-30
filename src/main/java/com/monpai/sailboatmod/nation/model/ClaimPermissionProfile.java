package com.monpai.sailboatmod.nation.model;

import net.minecraft.nbt.CompoundTag;

/**
 * Granular permission profile for a claimed chunk, controlling which roles
 * can perform each action type.
 */
public record ClaimPermissionProfile(
        NationClaimAccessLevel breakLevel,
        NationClaimAccessLevel placeLevel,
        NationClaimAccessLevel useLevel,
        NationClaimAccessLevel containerLevel,
        NationClaimAccessLevel redstoneLevel,
        NationClaimAccessLevel entityUseLevel,
        NationClaimAccessLevel entityDamageLevel
) {
    public static final ClaimPermissionProfile DEFAULT = new ClaimPermissionProfile(
            NationClaimAccessLevel.MEMBER,
            NationClaimAccessLevel.MEMBER,
            NationClaimAccessLevel.MEMBER,
            NationClaimAccessLevel.MEMBER,
            NationClaimAccessLevel.MEMBER,
            NationClaimAccessLevel.MEMBER,
            NationClaimAccessLevel.MEMBER
    );

    public ClaimPermissionProfile {
        if (breakLevel == null) breakLevel = NationClaimAccessLevel.MEMBER;
        if (placeLevel == null) placeLevel = NationClaimAccessLevel.MEMBER;
        if (useLevel == null) useLevel = NationClaimAccessLevel.MEMBER;
        if (containerLevel == null) containerLevel = NationClaimAccessLevel.MEMBER;
        if (redstoneLevel == null) redstoneLevel = NationClaimAccessLevel.MEMBER;
        if (entityUseLevel == null) entityUseLevel = NationClaimAccessLevel.MEMBER;
        if (entityDamageLevel == null) entityDamageLevel = NationClaimAccessLevel.MEMBER;
    }

    public ClaimPermissionProfile withAction(String actionId, NationClaimAccessLevel level) {
        if (actionId == null || level == null) return this;
        return switch (actionId.toLowerCase(java.util.Locale.ROOT)) {
            case "break" -> new ClaimPermissionProfile(level, placeLevel, useLevel, containerLevel, redstoneLevel, entityUseLevel, entityDamageLevel);
            case "place" -> new ClaimPermissionProfile(breakLevel, level, useLevel, containerLevel, redstoneLevel, entityUseLevel, entityDamageLevel);
            case "use" -> new ClaimPermissionProfile(breakLevel, placeLevel, level, containerLevel, redstoneLevel, entityUseLevel, entityDamageLevel);
            case "container" -> new ClaimPermissionProfile(breakLevel, placeLevel, useLevel, level, redstoneLevel, entityUseLevel, entityDamageLevel);
            case "redstone" -> new ClaimPermissionProfile(breakLevel, placeLevel, useLevel, containerLevel, level, entityUseLevel, entityDamageLevel);
            case "entity_use" -> new ClaimPermissionProfile(breakLevel, placeLevel, useLevel, containerLevel, redstoneLevel, level, entityDamageLevel);
            case "entity_damage" -> new ClaimPermissionProfile(breakLevel, placeLevel, useLevel, containerLevel, redstoneLevel, entityUseLevel, level);
            default -> this;
        };
    }

    public NationClaimAccessLevel getLevel(String actionId) {
        if (actionId == null) return NationClaimAccessLevel.MEMBER;
        return switch (actionId.toLowerCase(java.util.Locale.ROOT)) {
            case "break" -> breakLevel;
            case "place" -> placeLevel;
            case "use" -> useLevel;
            case "container" -> containerLevel;
            case "redstone" -> redstoneLevel;
            case "entity_use" -> entityUseLevel;
            case "entity_damage" -> entityDamageLevel;
            default -> NationClaimAccessLevel.MEMBER;
        };
    }

    public void saveTo(CompoundTag tag) {
        tag.putString("BreakAccessLevel", breakLevel.id());
        tag.putString("PlaceAccessLevel", placeLevel.id());
        tag.putString("UseAccessLevel", useLevel.id());
        tag.putString("ContainerAccessLevel", containerLevel.id());
        tag.putString("RedstoneAccessLevel", redstoneLevel.id());
        tag.putString("EntityUseAccessLevel", entityUseLevel.id());
        tag.putString("EntityDamageAccessLevel", entityDamageLevel.id());
    }

    public static ClaimPermissionProfile loadFrom(CompoundTag tag) {
        return new ClaimPermissionProfile(
                readLevel(tag, "BreakAccessLevel"),
                readLevel(tag, "PlaceAccessLevel"),
                readLevel(tag, "UseAccessLevel"),
                readLevel(tag, "ContainerAccessLevel"),
                readLevel(tag, "RedstoneAccessLevel"),
                readLevel(tag, "EntityUseAccessLevel"),
                readLevel(tag, "EntityDamageAccessLevel")
        );
    }

    private static NationClaimAccessLevel readLevel(CompoundTag tag, String key) {
        if (!tag.contains(key)) return NationClaimAccessLevel.MEMBER;
        NationClaimAccessLevel level = NationClaimAccessLevel.fromId(tag.getString(key));
        return level == null ? NationClaimAccessLevel.MEMBER : level;
    }
}
