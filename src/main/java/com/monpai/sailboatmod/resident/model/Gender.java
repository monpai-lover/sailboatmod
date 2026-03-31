package com.monpai.sailboatmod.resident.model;

public enum Gender {
    MALE("male", "Male"),
    FEMALE("female", "Female");

    private final String id;
    private final String displayName;

    Gender(String id, String displayName) { this.id = id; this.displayName = displayName; }
    public String id() { return id; }
    public String displayName() { return displayName; }

    public static Gender fromId(String id) {
        if ("female".equals(id)) return FEMALE;
        return MALE;
    }

    public static Gender random() {
        return java.util.concurrent.ThreadLocalRandom.current().nextBoolean() ? MALE : FEMALE;
    }
}
