package com.monpai.sailboatmod.route.terrain;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoverClassBlocksTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void worldCoverClassesMapToRepresentativeBlocks() {
        assertEquals(Blocks.OAK_LEAVES, CoverClassBlocks.blockFor(10).getBlock());
        assertEquals(Blocks.GRASS_BLOCK, CoverClassBlocks.blockFor(30).getBlock());
        assertEquals(Blocks.STONE_BRICKS, CoverClassBlocks.blockFor(50).getBlock());
        assertEquals(Blocks.SAND, CoverClassBlocks.blockFor(60).getBlock());
        assertEquals(Blocks.SNOW_BLOCK, CoverClassBlocks.blockFor(70).getBlock());
        assertEquals(Blocks.MANGROVE_LEAVES, CoverClassBlocks.blockFor(95).getBlock());
    }

    @Test
    void unknownClassFallsBackToGrass() {
        assertEquals(Blocks.GRASS_BLOCK, CoverClassBlocks.blockFor(0).getBlock());
        assertEquals(Blocks.GRASS_BLOCK, CoverClassBlocks.blockFor(255).getBlock());
    }
}
