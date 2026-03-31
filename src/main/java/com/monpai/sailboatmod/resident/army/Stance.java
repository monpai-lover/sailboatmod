package com.monpai.sailboatmod.resident.army;

public enum Stance {
    AGGRESSIVE("aggressive", "Aggressive"),
    DEFENSIVE("defensive", "Defensive"),
    HOLD("hold", "Hold Position"),
    PATROL("patrol", "Patrol");

    private final String id;
    private final String displayName;

    Stance(String id, String displayName) { this.id = id; this.displayName = displayName; }
    public String id() { return id; }
    public String displayName() { return displayName; }

    public static Stance fromId(String id) {
        if (id == null) return DEFENSIVE;
        for (Stance s : values()) if (s.id.equals(id)) return s;
        return DEFENSIVE;
    }
}
