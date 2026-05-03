package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.map.MapLod;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerTileTextureIdTest {
    @Test
    void textureRegistrationNameIsValidResourceLocationPathForLocalizedWorldAndLod() {
        RoadPlannerTileKey key = new RoadPlannerTileKey("新的世界", "minecraft:overworld", MapLod.LOD_1, 1, -3);

        String registrationName = RoadPlannerTile.textureRegistrationName(key);

        ResourceLocation location = assertDoesNotThrow(() -> new ResourceLocation("dynamic/" + registrationName + "_1"));
        assertEquals(location.getPath().toLowerCase(Locale.ROOT), location.getPath());
        assertTrue(location.getPath().contains("_lod_1_1_-3_1"));
    }
}
