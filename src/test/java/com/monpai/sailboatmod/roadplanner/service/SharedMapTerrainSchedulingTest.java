package com.monpai.sailboatmod.roadplanner.service;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class SharedMapTerrainSchedulingTest {
    private record Tile(int id, boolean complete) { }

    @Test
    void completedRecentSubscriptionsCannotStarveOlderPendingTiles() throws Exception {
        List<Tile> tiles = new ArrayList<>();
        for (int id = 0; id < 12; id++) tiles.add(new Tile(id, id >= 4));

        assertEquals(List.of(3, 2, 1, 0), pending(tiles, 8).stream().map(Tile::id).toList(),
                "eight retained complete subscriptions must not consume the eight available actual-terrain work slots");
    }

    @Test
    void currentViewportWorkRemainsRecentFirstAndBounded() throws Exception {
        List<Tile> tiles = new ArrayList<>();
        for (int id = 0; id < 12; id++) tiles.add(new Tile(id, false));

        assertEquals(List.of(11, 10, 9, 8, 7, 6, 5, 4), pending(tiles, 8).stream().map(Tile::id).toList());
    }

    @Test
    void northChunkChangesAlsoInvalidateTheDependentSouthHaloAcrossNegativeTileBoundaries() throws Exception {
        long[] chunks;
        try {
            chunks = (long[]) SharedMapTerrainService.class.getDeclaredMethod("dependentChunks", int.class, int.class)
                    .invoke(null, -17, -17);
        } catch (NoSuchMethodException missing) {
            chunks = fail("adjacent-north shading requires changes to refresh the south chunk too");
        }
        assertArrayEquals(new long[]{ChunkPos.asLong(-17, -17), ChunkPos.asLong(-17, -16)}, chunks);
    }

    @SuppressWarnings("unchecked")
    private static List<Tile> pending(List<Tile> tiles, int budget) throws Exception {
        try {
            return (List<Tile>) SharedMapTerrainService.class.getDeclaredMethod("recentPending",
                    Collection.class, Predicate.class, int.class)
                    .invoke(null, tiles, (Predicate<Tile>) tile -> !tile.complete, budget);
        } catch (NoSuchMethodException missing) {
            return fail("shared actual-map work must skip completed retained subscriptions before applying its budget");
        }
    }
}
