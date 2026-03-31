package com.monpai.sailboatmod.resident.model;

public enum Profession {
    UNEMPLOYED("unemployed", "Unemployed", false),
    FARMER("farmer", "Farmer", true),
    MINER("miner", "Miner", true),
    LUMBERJACK("lumberjack", "Lumberjack", true),
    FISHERMAN("fisherman", "Fisherman", true),
    BLACKSMITH("blacksmith", "Blacksmith", true),
    BAKER("baker", "Baker", true),
    GUARD("guard", "Guard", false),
    SOLDIER("soldier", "Soldier", false),
    BUILDER("builder", "Builder", false);

    private final String id;
    private final String displayName;
    private final boolean produces;

    Profession(String id, String displayName, boolean produces) {
        this.id = id;
        this.displayName = displayName;
        this.produces = produces;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public boolean produces() { return produces; }
    public boolean isCivilian() { return this != GUARD && this != SOLDIER && this != UNEMPLOYED; }

    public static Profession fromId(String id) {
        if (id == null || id.isBlank()) return UNEMPLOYED;
        for (Profession p : values()) {
            if (p.id.equals(id)) return p;
        }
        return UNEMPLOYED;
    }
}
