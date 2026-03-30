package com.monpai.sailboatmod.nation.model;

import net.minecraft.nbt.CompoundTag;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public record NationRecord(
        String nationId,
        String name,
        String shortName,
        int primaryColorRgb,
        int secondaryColorRgb,
        UUID leaderUuid,
        long createdAt,
        String capitalTownId,
        String coreDimension,
        long corePos,
        String flagId
) {
    public static final int MIN_NAME_LENGTH = 3;
    public static final int MAX_NAME_LENGTH = 24;
    public static final int MAX_SHORT_NAME_LENGTH = 12;
    private static final long NO_CORE_POS = Long.MIN_VALUE;
    private static final Set<String> RESERVED_NAMES = Set.of(
            "admin",
            "administrator",
            "console",
            "moderator",
            "nation",
            "nations",
            "none",
            "null",
            "operator",
            "server",
            "staff",
            "system"
    );

    public NationRecord {
        nationId = sanitizeId(nationId);
        name = normalizeName(name);
        shortName = normalizeShortName(shortName);
        primaryColorRgb = clampRgb(primaryColorRgb);
        secondaryColorRgb = clampRgb(secondaryColorRgb);
        leaderUuid = leaderUuid == null ? new UUID(0L, 0L) : leaderUuid;
        capitalTownId = sanitizeId(capitalTownId);
        coreDimension = sanitizeId(coreDimension);
        flagId = sanitizeId(flagId);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("NationId", nationId);
        tag.putString("Name", name);
        tag.putString("ShortName", shortName);
        tag.putInt("PrimaryColor", primaryColorRgb);
        tag.putInt("SecondaryColor", secondaryColorRgb);
        tag.putUUID("LeaderUuid", leaderUuid);
        tag.putLong("CreatedAt", createdAt);
        tag.putString("CapitalTownId", capitalTownId);
        tag.putString("CoreDimension", coreDimension);
        tag.putLong("CorePos", corePos);
        tag.putString("FlagId", flagId);
        return tag;
    }

    public static NationRecord load(CompoundTag tag) {
        UUID leaderUuid = tag.hasUUID("LeaderUuid") ? tag.getUUID("LeaderUuid") : new UUID(0L, 0L);
        return new NationRecord(
                tag.getString("NationId"),
                tag.getString("Name"),
                tag.getString("ShortName"),
                tag.getInt("PrimaryColor"),
                tag.getInt("SecondaryColor"),
                leaderUuid,
                tag.getLong("CreatedAt"),
                tag.getString("CapitalTownId"),
                tag.getString("CoreDimension"),
                tag.contains("CorePos") ? tag.getLong("CorePos") : NO_CORE_POS,
                tag.getString("FlagId")
        );
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

    public static String normalizeShortName(String rawShortName) {
        return rawShortName == null ? "" : rawShortName.trim();
    }

    public static boolean isValidShortName(String shortName) {
        return shortName != null
                && !shortName.isBlank()
                && shortName.length() <= MAX_SHORT_NAME_LENGTH
                && shortName.matches("[\\p{L}\\p{N}_-]+");
    }

    public static String buildShortName(String name) {
        String normalized = normalizeName(name);
        if (normalized.isBlank()) {
            return "NAT";
        }
        String[] parts = normalized.split(" ");
        StringBuilder initials = new StringBuilder();
        for (String part : parts) {
            if (!part.isBlank() && Character.isLetterOrDigit(part.charAt(0))) {
                initials.append(Character.toUpperCase(part.charAt(0)));
            }
            if (initials.length() >= 4) {
                break;
            }
        }
        if (initials.length() >= 2) {
            return initials.toString();
        }

        String compact = normalized.replaceAll("[^\\p{L}\\p{N}]", "").toUpperCase(Locale.ROOT);
        return compact.isBlank() ? "NAT" : compact.substring(0, Math.min(4, compact.length()));
    }

    public static boolean isValidRgb(int color) {
        return (color & 0xFF000000) == 0;
    }

    public static Integer parseHexColor(String rawHex) {
        if (rawHex == null) {
            return null;
        }
        String normalized = rawHex.trim();
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.matches("[0-9A-Fa-f]{6}")) {
            return null;
        }
        try {
            return Integer.parseInt(normalized, 16) & 0x00FFFFFF;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int clampRgb(int color) {
        return color & 0x00FFFFFF;
    }

    private static String sanitizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
