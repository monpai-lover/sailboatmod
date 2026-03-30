package com.monpai.sailboatmod.nation.model;

import net.minecraft.nbt.CompoundTag;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public record TownRecord(
        String townId,
        String nationId,
        String name,
        UUID mayorUuid,
        long createdAt,
        String coreDimension,
        long corePos,
        String flagId
) {
    public static final int MIN_NAME_LENGTH = 3;
    public static final int MAX_NAME_LENGTH = 24;
    private static final long NO_CORE_POS = Long.MIN_VALUE;
    private static final Set<String> RESERVED_NAMES = Set.of(
            "admin",
            "administrator",
            "capital",
            "city",
            "console",
            "moderator",
            "nation",
            "server",
            "staff",
            "system",
            "town"
    );

    public TownRecord {
        townId = sanitizeId(townId);
        nationId = sanitizeId(nationId);
        name = normalizeName(name);
        mayorUuid = mayorUuid == null ? new UUID(0L, 0L) : mayorUuid;
        coreDimension = sanitizeId(coreDimension);
        flagId = sanitizeId(flagId);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("TownId", townId);
        tag.putString("NationId", nationId);
        tag.putString("Name", name);
        tag.putUUID("MayorUuid", mayorUuid);
        tag.putLong("CreatedAt", createdAt);
        tag.putString("CoreDimension", coreDimension);
        tag.putLong("CorePos", corePos);
        tag.putString("FlagId", flagId);
        return tag;
    }

    public static TownRecord load(CompoundTag tag) {
        UUID mayorUuid = tag.hasUUID("MayorUuid") ? tag.getUUID("MayorUuid") : new UUID(0L, 0L);
        return new TownRecord(
                tag.getString("TownId"),
                tag.getString("NationId"),
                tag.getString("Name"),
                mayorUuid,
                tag.getLong("CreatedAt"),
                tag.getString("CoreDimension"),
                tag.contains("CorePos") ? tag.getLong("CorePos") : NO_CORE_POS,
                tag.getString("FlagId")
        );
    }

    public boolean hasNation() {
        return !nationId.isBlank();
    }

    public boolean hasCore() {
        return corePos != NO_CORE_POS && !coreDimension.isBlank();
    }

    public static long noCorePos() {
        return NO_CORE_POS;
    }

    public static String normalizeName(String rawName) {
        if (rawName == null) {
            return "";
        }
        return rawName.trim().replaceAll("\\s+", " ");
    }

    public static boolean isValidName(String name) {
        return name != null
                && name.length() >= MIN_NAME_LENGTH
                && name.length() <= MAX_NAME_LENGTH
                && name.matches("[\\p{L}\\p{N}_ -]+");
    }

    public static boolean isReservedName(String name) {
        String normalized = normalizeName(name).toLowerCase(Locale.ROOT);
        return !normalized.isBlank() && RESERVED_NAMES.contains(normalized);
    }

    private static String sanitizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
