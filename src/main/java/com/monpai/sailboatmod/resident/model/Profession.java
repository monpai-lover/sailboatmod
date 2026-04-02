package com.monpai.sailboatmod.resident.model;

public enum Profession {
    UNEMPLOYED("unemployed", "Unemployed", false, EducationLevel.ILLITERATE),
    FARMER("farmer", "Farmer", true, EducationLevel.ILLITERATE),
    MINER("miner", "Miner", true, EducationLevel.ILLITERATE),
    LUMBERJACK("lumberjack", "Lumberjack", true, EducationLevel.ILLITERATE),
    FISHERMAN("fisherman", "Fisherman", true, EducationLevel.PRIMARY),
    BLACKSMITH("blacksmith", "Blacksmith", true, EducationLevel.PRIMARY),
    BAKER("baker", "Baker", true, EducationLevel.PRIMARY),
    GUARD("guard", "Guard", false, EducationLevel.SECONDARY),
    SOLDIER("soldier", "Soldier", false, EducationLevel.SECONDARY),
    BUILDER("builder", "Builder", false, EducationLevel.SECONDARY),
    TEACHER("teacher", "Teacher", false, EducationLevel.UNIVERSITY);

    private final String id;
    private final String displayName;
    private final boolean produces;
    private final EducationLevel minEducation;

    Profession(String id, String displayName, boolean produces, EducationLevel minEducation) {
        this.id = id;
        this.displayName = displayName;
        this.produces = produces;
        this.minEducation = minEducation;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public boolean produces() { return produces; }
    public EducationLevel minEducation() { return minEducation; }
    public boolean isCivilian() { return this != GUARD && this != SOLDIER && this != UNEMPLOYED; }

    public boolean qualifiesFor(EducationLevel education) {
        return education != null && education.tier() >= minEducation.tier();
    }

    public static Profession fromId(String id) {
        if (id == null || id.isBlank()) return UNEMPLOYED;
        for (Profession p : values()) {
            if (p.id.equals(id)) return p;
        }
        return UNEMPLOYED;
    }
}
