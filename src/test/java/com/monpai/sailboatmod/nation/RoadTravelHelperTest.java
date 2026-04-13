package com.monpai.sailboatmod.nation;

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
