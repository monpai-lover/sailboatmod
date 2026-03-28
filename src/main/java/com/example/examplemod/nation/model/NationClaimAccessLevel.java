package com.example.examplemod.nation.model;

import java.util.Locale;

public enum NationClaimAccessLevel {
    LEADER("leader", 0),
    OFFICER("officer", 10),
    MEMBER("member", 20);

    private final String id;
    private final int maxPriority;

    NationClaimAccessLevel(String id, int maxPriority) {
        this.id = id;
        this.maxPriority = maxPriority;
    }

    public String id() {
        return id;
    }

    public int maxPriority() {
        return maxPriority;
    }

    public static NationClaimAccessLevel fromId(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return MEMBER;
        }
        String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
        for (NationClaimAccessLevel value : values()) {
            if (value.id.equals(normalized)) {
                return value;
            }
        }
        return null;
    }
}
