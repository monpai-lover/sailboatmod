package com.monpai.sailboatmod.nation;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadTravelHelperTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void grantsRoadSpeedWhenStandingDirectlyOnRoadSurface() {
        assertTrue(RoadTravelHelper.shouldGrantRoadSpeed(
                Blocks.STONE_BRICK_SLAB.defaultBlockState(),
                Blocks.DIRT.defaultBlockState()
        ));
        assertTrue(RoadTravelHelper.shouldGrantRoadSpeed(
                Blocks.MUD_BRICK_STAIRS.defaultBlockState(),
                Blocks.DIRT.defaultBlockState()
        ));
    }

    @Test
    void grantsRoadSpeedWhenRoadSurfaceIsOneBlockBelowFeet() {
        assertTrue(RoadTravelHelper.shouldGrantRoadSpeed(
                Blocks.AIR.defaultBlockState(),
                Blocks.SMOOTH_SANDSTONE_SLAB.defaultBlockState()
        ));
    }

    @Test
    void grantsRoadSpeedForAllRoadPlannerMaterialPresets() {
        for (String preset : new String[]{
                "smooth_stone",
                "cobblestone",
                "oak_planks",
                "spruce_planks",
                "stone_bricks"
        }) {
            assertTrue(RoadTravelHelper.isWalkableRoadSurface(
                    RoadPlannerBuildSettings.blockFor(preset).defaultBlockState()
            ), "road planner surface preset should be walkable: " + preset);
            assertTrue(RoadTravelHelper.isWalkableRoadSurface(
                    RoadPlannerBuildSettings.slabFor(preset).defaultBlockState()
            ), "road planner slab preset should be walkable: " + preset);
        }
    }

    @Test
    void doesNotTreatRoadsideLightsOrNaturalBlocksAsRoadSurface() {
        assertFalse(RoadTravelHelper.shouldGrantRoadSpeed(
                Blocks.COBBLESTONE_WALL.defaultBlockState(),
                Blocks.DIRT.defaultBlockState()
        ));
        assertFalse(RoadTravelHelper.shouldGrantRoadSpeed(
                Blocks.AIR.defaultBlockState(),
                Blocks.LANTERN.defaultBlockState()
        ));
        assertFalse(RoadTravelHelper.shouldGrantRoadSpeed(
                Blocks.GRASS_BLOCK.defaultBlockState(),
                Blocks.DIRT.defaultBlockState()
        ));
    }
}
