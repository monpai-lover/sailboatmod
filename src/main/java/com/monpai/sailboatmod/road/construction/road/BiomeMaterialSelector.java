package com.monpai.sailboatmod.road.construction.road;

import com.monpai.sailboatmod.road.model.RoadMaterial;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

public class BiomeMaterialSelector {
    public static String normalizePresetId(String materialPreset) {
        if (materialPreset == null || materialPreset.isBlank()) {
            return "";
        }
        String normalized = materialPreset.trim().toLowerCase(java.util.Locale.ROOT);
        return "auto".equals(normalized) ? "" : normalized;
    }

    public static RoadMaterial selectPreset(String materialPreset) {
        String normalized = normalizePresetId(materialPreset);
        if (normalized.isBlank()) {
            return null;
        }
        return switch (normalized) {
            case "stone_brick" -> RoadMaterial.STONE_BRICK;
            case "sandstone" -> RoadMaterial.SANDSTONE;
            case "cobblestone" -> RoadMaterial.COBBLESTONE;
            default -> null;
        };
    }

    public RoadMaterial select(String materialPreset, Holder<Biome> biome) {
        RoadMaterial preset = selectPreset(materialPreset);
        return preset != null ? preset : select(biome);
    }

    public RoadMaterial select(Holder<Biome> biome) {
        if (biome.is(BiomeTags.IS_BADLANDS)) {
            return RoadMaterial.RED_SANDSTONE;
        }
        if (biome.is(Biomes.DESERT)) {
            return RoadMaterial.SANDSTONE;
        }
        if (biome.is(Biomes.SWAMP) || biome.is(Biomes.MANGROVE_SWAMP)) {
            return RoadMaterial.MOSSY_COBBLE;
        }
        if (biome.is(BiomeTags.IS_TAIGA) || biome.is(BiomeTags.IS_FOREST)) {
            return RoadMaterial.DIRT_PATH;
        }
        if (biome.is(Biomes.SNOWY_PLAINS) || biome.is(Biomes.SNOWY_TAIGA)
                || biome.is(Biomes.FROZEN_RIVER) || biome.is(Biomes.ICE_SPIKES)) {
            return RoadMaterial.COBBLESTONE;
        }
        return RoadMaterial.STONE_BRICK;
    }
}
