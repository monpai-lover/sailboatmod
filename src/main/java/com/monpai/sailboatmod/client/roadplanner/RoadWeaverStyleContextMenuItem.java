package com.monpai.sailboatmod.client.roadplanner;

public record RoadWeaverStyleContextMenuItem(String label,
                                            RoadPlannerContextMenuAction action,
                                            boolean enabled,
                                            boolean separator) {
    public static RoadWeaverStyleContextMenuItem action(String label, RoadPlannerContextMenuAction action) {
        return new RoadWeaverStyleContextMenuItem(label, action, true, false);
    }

    public static RoadWeaverStyleContextMenuItem divider() {
        return new RoadWeaverStyleContextMenuItem("", null, false, true);
    }

    public RoadWeaverStyleContextMenuItem withEnabled(boolean enabled) {
        return new RoadWeaverStyleContextMenuItem(label, action, enabled, separator);
    }
}
