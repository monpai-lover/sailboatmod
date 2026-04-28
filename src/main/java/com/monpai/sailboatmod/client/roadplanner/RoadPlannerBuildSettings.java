package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;

public record RoadPlannerBuildSettings(int width, String materialPreset, boolean streetlightsEnabled) {
    public static final RoadPlannerBuildSettings DEFAULTS = new RoadPlannerBuildSettings(5, "smooth_stone", true);

    public RoadPlannerBuildSettings {
        width = normalizeWidth(width);
        materialPreset = normalizeMaterial(materialPreset);
    }

    public static int normalizeWidth(int width) {
        return width <= 3 ? 3 : (width <= 5 ? 5 : 7);
    }

    public static String normalizeMaterial(String materialPreset) {
        if (materialPreset == null || materialPreset.isBlank()) {
            return DEFAULTS.materialPreset();
        }
        return materialPreset.trim().toLowerCase(Locale.ROOT);
    }

    public BlockState surfaceState() {
        return blockFor(materialPreset).defaultBlockState();
    }

    public static Block blockFor(String materialPreset) {
        return switch (normalizeMaterial(materialPreset)) {
            case "stone_bricks", "stone_brick" -> Blocks.STONE_BRICKS;
            case "cobblestone" -> Blocks.COBBLESTONE;
            case "oak_planks" -> Blocks.OAK_PLANKS;
            case "spruce_planks" -> Blocks.SPRUCE_PLANKS;
            default -> Blocks.SMOOTH_STONE;
        };
    }
}
