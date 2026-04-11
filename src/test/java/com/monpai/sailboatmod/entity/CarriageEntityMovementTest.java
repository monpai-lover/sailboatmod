package com.monpai.sailboatmod.entity;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarriageEntityMovementTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void recognizesFinishedRoadLikeSurfaces() {
        assertTrue(CarriageEntity.isRoadSurfaceForTest(Blocks.STONE_BRICKS.defaultBlockState()));
        assertTrue(CarriageEntity.isRoadSurfaceForTest(Blocks.STONE_BRICK_SLAB.defaultBlockState()));
        assertTrue(CarriageEntity.isRoadSurfaceForTest(Blocks.STONE_BRICK_STAIRS.defaultBlockState()));
        assertFalse(CarriageEntity.isRoadSurfaceForTest(Blocks.DIRT.defaultBlockState()));
    }
}
