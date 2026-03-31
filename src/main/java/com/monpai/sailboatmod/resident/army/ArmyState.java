package com.monpai.sailboatmod.resident.army;

public enum ArmyState {
    IDLE("idle"),
    RALLYING("rallying"),
    MARCHING("marching"),
    ATTACKING("attacking"),
    DEFENDING("defending"),
    RETREATING("retreating");

    private final String id;
    ArmyState(String id) { this.id = id; }
    public String id() { return id; }

    public static ArmyState fromId(String id) {
        if (id == null) return IDLE;
        for (ArmyState s : values()) if (s.id.equals(id)) return s;
        return IDLE;
    }
}
