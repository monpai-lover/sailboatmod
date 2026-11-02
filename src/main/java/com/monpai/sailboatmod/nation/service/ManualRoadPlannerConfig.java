package com.monpai.sailboatmod.nation.service;

public record ManualRoadPlannerConfig(int width, String materialPreset, boolean tunnelEnabled) {
    public static final int DEFAULT_WIDTH = 3;
    public static final String DEFAULT_MATERIAL_PRESET = "auto";

    public ManualRoadPlannerConfig {
        materialPreset = materialPreset == null ? DEFAULT_MATERIAL_PRESET : materialPreset;
    }

    public static ManualRoadPlannerConfig normalized(int width, String materialPreset, boolean tunnelEnabled) {
        int normalizedWidth = width <= 3 ? 3 : (width <= 5 ? 5 : 7);
        String normalizedMaterial = materialPreset == null || materialPreset.isBlank()
                ? DEFAULT_MATERIAL_PRESET
                : materialPreset.trim().toLowerCase(java.util.Locale.ROOT);
        return new ManualRoadPlannerConfig(normalizedWidth, normalizedMaterial, tunnelEnabled);
    }

    public static ManualRoadPlannerConfig defaults() {
        return new ManualRoadPlannerConfig(DEFAULT_WIDTH, DEFAULT_MATERIAL_PRESET, false);
    }
}
