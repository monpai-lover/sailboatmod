package com.monpai.sailboatmod.route.water;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaterSurfaceRulesTest {
    private static final int MIN_Y = -64;
    private static final int MAX_Y = 320;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void idRulesMatchLegacyReader() {
        for (String id : new String[]{"minecraft:water", "minecraft:bubble_column", "minecraft:seagrass",
                "minecraft:tall_seagrass", "minecraft:kelp", "minecraft:kelp_plant"}) {
            assertTrue(WaterSurfaceRules.isNavigableSurface(id, false), id);
        }
        assertFalse(WaterSurfaceRules.isNavigableSurface("minecraft:stone", false));
        assertTrue(WaterSurfaceRules.isNavigableSurface("minecraft:oak_slab", true));
    }

    @Test
    void blockStateOverloadReadsWaterlogged() {
        assertTrue(WaterSurfaceRules.isNavigableSurface(
                Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true)));
        assertFalse(WaterSurfaceRules.isNavigableSurface(Blocks.OAK_SLAB.defaultBlockState()));
        assertFalse(WaterSurfaceRules.isNavigableSurface(Blocks.SAND.defaultBlockState()));
        assertTrue(WaterSurfaceRules.isNavigableSurface(Blocks.WATER.defaultBlockState()));
    }

    @Test
    void scanSkipsSurfaceNoiseAndAir() {
        // every column: stone below 60, water 60..62, short grass at 63, air above
        NbtChunkWaterReader.ChunkColumns columns = WaterSurfaceRules.scanChunk((x, y, z) -> stack(y), MIN_Y, MAX_Y);
        assertTrue(columns.water()[5 * 16 + 3]);
        assertEquals(62, columns.surfaceY()[5 * 16 + 3]);
    }

    @Test
    void emptyColumnIsLandAtMinus999() {
        NbtChunkWaterReader.ChunkColumns columns = WaterSurfaceRules.scanChunk(
                (x, y, z) -> x == 0 && z == 0 ? Blocks.AIR.defaultBlockState() : stack(y), MIN_Y, MAX_Y);
        assertFalse(columns.water()[0]);
        assertEquals(-999, columns.surfaceY()[0]);
        assertTrue(columns.water()[1]);
    }

    @Test
    void scanStartsAtPerColumnTop() {
        // a column whose stated top is 59 never sees the water above it
        NbtChunkWaterReader.ChunkColumns columns = WaterSurfaceRules.scanChunk((x, y, z) -> stack(y),
                (x, z) -> x == 0 && z == 0 ? 59 : 63, MIN_Y);
        assertFalse(columns.water()[0]);
        assertEquals(59, columns.surfaceY()[0]);
        assertTrue(columns.water()[1]);
    }

    private static BlockState stack(int y) {
        if (y < 60) {
            return Blocks.STONE.defaultBlockState();
        }
        if (y <= 62) {
            return Blocks.WATER.defaultBlockState();
        }
        if (y == 63) {
            return Blocks.SHORT_GRASS.defaultBlockState();
        }
        return Blocks.AIR.defaultBlockState();
    }
}
