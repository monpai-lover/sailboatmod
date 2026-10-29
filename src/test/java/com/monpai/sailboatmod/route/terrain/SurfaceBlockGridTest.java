package com.monpai.sailboatmod.route.terrain;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SurfaceBlockGridTest {
    private final List<int[]> lookups = new ArrayList<>();

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private BlockState resolve(int x, int z) {
        lookups.add(new int[]{x, z});
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    @Test
    void oneLookupPer4x4PixelCell() {
        SurfaceBlockGrid grid = new SurfaceBlockGrid(1024);
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                grid.get(x, z, 1, this::resolve);
            }
        }

        assertEquals(4, lookups.size(), "an 8x8 block area at 1 block per pixel is four 4x4 cells");
    }

    @Test
    void cellScalesWithBlocksPerSampleAndUsesTheCellCentre() {
        SurfaceBlockGrid grid = new SurfaceBlockGrid(1024);
        grid.get(5, 70, 16, this::resolve); // cell size 64 blocks

        assertEquals(1, lookups.size());
        assertEquals(32, lookups.get(0)[0]);
        assertEquals(96, lookups.get(0)[1]);
    }

    @Test
    void negativeCoordinatesUseFloorCells() {
        SurfaceBlockGrid grid = new SurfaceBlockGrid(1024);
        grid.get(-1, -1, 1, this::resolve);
        grid.get(-4, -4, 1, this::resolve);
        grid.get(0, 0, 1, this::resolve);

        assertEquals(2, lookups.size(), "(-1,-1) and (-4,-4) share the cell [-4,0); (0,0) is the next one");
        assertEquals(-2, lookups.get(0)[0]);
    }

    @Test
    void evictsOldestBeyondCapacity() {
        SurfaceBlockGrid grid = new SurfaceBlockGrid(2);
        grid.get(0, 0, 1, this::resolve);
        grid.get(4, 0, 1, this::resolve);
        grid.get(8, 0, 1, this::resolve);
        grid.get(0, 0, 1, this::resolve);

        assertEquals(4, lookups.size(), "the first cell was evicted and resolved again");
    }
}
