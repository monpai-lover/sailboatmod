package com.monpai.sailboatmod.route.water.lookahead;

import com.monpai.sailboatmod.route.water.NbtChunkWaterReader;
import com.monpai.sailboatmod.route.water.RealBlockWaterMap;
import net.minecraft.world.level.ChunkPos;

import java.util.HashSet;
import java.util.Set;

/** Test maps: chunks 0..15 x 0..3 (blocks x 0..255, z 0..63) open water with a 1-wide land wall at x=100, z 0..wallEnd. */
final class LookaheadMaps {
    static final Set<Long> LOADED = new HashSet<>();

    static {
        for (int cx = 0; cx < 16; cx++) {
            for (int cz = 0; cz < 4; cz++) {
                LOADED.add(ChunkPos.asLong(cx, cz));
            }
        }
    }

    private LookaheadMaps() {
    }

    static RealBlockWaterMap wall(int wallEndZ) {
        RealBlockWaterMap map = new RealBlockWaterMap(null, 63);
        for (int cx = 0; cx < 16; cx++) {
            for (int cz = 0; cz < 4; cz++) {
                map.seedChunk(cx, cz, columns(cx, cz, wallEndZ));
            }
        }
        return map;
    }

    static NbtChunkWaterReader.ChunkColumns columns(int cx, int cz, int wallEndZ) {
        boolean[] water = new boolean[256];
        int[] surface = new int[256];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int x = (cx << 4) + lx;
                int z = (cz << 4) + lz;
                water[lz * 16 + lx] = !(x == 100 && z <= wallEndZ);
                surface[lz * 16 + lx] = 62;
            }
        }
        return new NbtChunkWaterReader.ChunkColumns(water, surface);
    }
}
