package com.monpai.sailboatmod.resident.army;

public enum Formation {
    LINE("line", "Line", 0),
    COLUMN("column", "Column", 1),
    WEDGE("wedge", "Wedge", 2),
    SQUARE("square", "Square", 3),
    SCATTER("scatter", "Scatter", 4);

    private final String id;
    private final String displayName;
    private final int index;

    Formation(String id, String displayName, int index) {
        this.id = id; this.displayName = displayName; this.index = index;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public int index() { return index; }

    public static Formation fromId(String id) {
        if (id == null) return LINE;
        for (Formation f : values()) if (f.id.equals(id)) return f;
        return LINE;
    }

    /**
     * Get the offset position for a soldier at the given index within this formation.
     * Returns [dx, dz] relative to the rally point, facing direction is +Z.
     */
    public int[] getSlotOffset(int soldierIndex, int totalSoldiers) {
        return switch (this) {
            case LINE -> new int[]{ soldierIndex - totalSoldiers / 2, 0 };
            case COLUMN -> new int[]{ 0, -soldierIndex };
            case WEDGE -> {
                // V shape: leader at front, others fan out behind
                if (soldierIndex == 0) yield new int[]{ 0, 0 };
                int row = (soldierIndex + 1) / 2;
                int side = (soldierIndex % 2 == 1) ? -1 : 1;
                yield new int[]{ side * row, -row };
            }
            case SQUARE -> {
                int side = (int) Math.ceil(Math.sqrt(totalSoldiers));
                yield new int[]{ (soldierIndex % side) - side / 2, -(soldierIndex / side) };
            }
            case SCATTER -> {
                // Pseudo-random spread using index as seed
                int hash = soldierIndex * 7919 + 104729;
                int dx = (hash % 7) - 3;
                int dz = ((hash / 7) % 7) - 3;
                yield new int[]{ dx, dz };
            }
        };
    }
}
