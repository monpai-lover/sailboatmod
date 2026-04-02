package com.monpai.sailboatmod.resident.model;

/**
 * Education levels inspired by Victoria 3
 */
public enum EducationLevel {
    ILLITERATE("illiterate", "Illiterate", 0),
    PRIMARY("primary", "Primary Education", 1),
    SECONDARY("secondary", "Secondary Education", 2),
    UNIVERSITY("university", "University Education", 3);

    private final String id;
    private final String displayName;
    private final int tier;

    EducationLevel(String id, String displayName, int tier) {
        this.id = id;
        this.displayName = displayName;
        this.tier = tier;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public int tier() { return tier; }

    public static EducationLevel fromId(String id) {
        if (id == null || id.isBlank()) return ILLITERATE;
        for (EducationLevel level : values()) {
            if (level.id.equals(id)) return level;
        }
        return ILLITERATE;
    }

    public static EducationLevel fromTier(int tier) {
        for (EducationLevel level : values()) {
            if (level.tier == tier) return level;
        }
        return ILLITERATE;
    }
}
