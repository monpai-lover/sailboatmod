package com.monpai.sailboatmod.roadplanner.map;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class SharedMapSavedChunkTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test
    void fullSavedChunkRendersWithoutLoadingOrGeneratingAnyWorldChunk() throws Exception {
        int[] pixels = read(flat("minecraft:grass_block", "minecraft:full"), -17, 31);
        assertEquals(256, pixels.length);
        int expected = RoadMapRenderStyle.styleTerrainByDelta(MapBlockColors.lookup("minecraft:grass_block"), 0);
        assertEquals(expected, pixels[0]);
        assertEquals(expected, pixels[255]);
    }

    @Test
    void absentOrPartlyGeneratedChunkProducesNoActualCoverage() throws Exception {
        assertEquals(0, read(null, 0, 0).length);
        assertEquals(0, read(flat("minecraft:stone", "minecraft:noise"), 0, 0).length);
    }

    @Test
    void actualSavedChunkUsesImmediateNorthNbtForTheFirstRow() throws Exception {
        CompoundTag north = flat("minecraft:grass_block", "minecraft:full");
        north.getList("sections", 10).getCompound(0).putByte("Y", (byte) -1);
        int[] pixels;
        try {
            Class<?> type = Class.forName("com.monpai.sailboatmod.roadplanner.map.SharedMapSavedChunk");
            pixels = (int[]) type.getMethod("read", CompoundTag.class, CompoundTag.class,
                    int.class, int.class, int.class, int.class).invoke(null,
                    flat("minecraft:grass_block", "minecraft:full"), north, 0, 0, -16, 32);
        } catch (ClassNotFoundException | NoSuchMethodException missing) {
            pixels = fail("actual NBT rendering needs a north halo too, not a neutral stripe at every chunk boundary");
        }
        int grass = MapBlockColors.lookup("minecraft:grass_block");
        assertEquals(RoadMapRenderStyle.styleTerrainByDelta(grass, 16), pixels[0]);
        assertEquals(RoadMapRenderStyle.styleTerrainByDelta(grass, 0), pixels[16]);
    }

    private static int[] read(CompoundTag tag, int chunkX, int chunkZ) throws Exception {
        try {
            Class<?> type = Class.forName("com.monpai.sailboatmod.roadplanner.map.SharedMapSavedChunk");
            Method read = type.getMethod("read", CompoundTag.class, int.class, int.class, int.class, int.class);
            return (int[]) read.invoke(null, tag, chunkX, chunkZ, 0, 32);
        } catch (ClassNotFoundException | NoSuchMethodException missing) {
            return fail("automatic map views must decode existing FULL NBT instead of force-loading/generating chunks");
        }
    }

    private static CompoundTag flat(String block, String status) {
        CompoundTag chunk = new CompoundTag();
        chunk.putString("Status", status);
        CompoundTag section = new CompoundTag();
        section.putByte("Y", (byte) 0);
        CompoundTag state = new CompoundTag();
        state.putString("Name", block);
        ListTag palette = new ListTag();
        palette.add(state);
        CompoundTag states = new CompoundTag();
        states.put("palette", palette);
        section.put("block_states", states);
        ListTag sections = new ListTag();
        sections.add(section);
        chunk.put("sections", sections);
        return chunk;
    }
}
