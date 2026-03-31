package com.monpai.sailboatmod.resident.model;

import java.util.concurrent.ThreadLocalRandom;

public final class ResidentNames {
    private static final String[] FIRST_NAMES = {
            "Ada", "Bjorn", "Celia", "Dag", "Elara", "Finn", "Greta", "Hans",
            "Iris", "Jorn", "Kira", "Lars", "Mira", "Nils", "Olga", "Per",
            "Quinn", "Rolf", "Siri", "Tor", "Una", "Vigo", "Wren", "Xia",
            "Yara", "Zeke", "Alma", "Bram", "Cora", "Dex", "Elin", "Frey",
            "Gus", "Hild", "Ivan", "Jade", "Karl", "Lena", "Max", "Nora",
            "Otto", "Pia", "Rex", "Saga", "Tove", "Ulf", "Vera", "Wulf"
    };

    private static final String[] LAST_NAMES = {
            "Stone", "Brook", "Field", "Hill", "Wood", "Dale", "Marsh", "Glen",
            "Forge", "Mill", "Thatch", "Bloom", "Frost", "Ash", "Thorn", "Reed",
            "Croft", "Holm", "Fell", "Moor", "Wold", "Holt", "Wick", "Stead",
            "Barrow", "Cliff", "Dune", "Fern", "Grove", "Heath", "Knoll", "Lake"
    };

    public static String random() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return FIRST_NAMES[r.nextInt(FIRST_NAMES.length)] + " " + LAST_NAMES[r.nextInt(LAST_NAMES.length)];
    }

    private ResidentNames() {}
}
