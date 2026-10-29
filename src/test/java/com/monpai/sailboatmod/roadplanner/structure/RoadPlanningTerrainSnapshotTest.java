package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.route.terrain.GeneratorColumnPredictor;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class RoadPlanningTerrainSnapshotTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test
    void ownedActualColumnsWinAndMissingColumnsPredictOnceWithoutFabricatingUnknownTerrain() throws Exception {
        AtomicInteger predicted = new AtomicInteger();
        RoadTerrainSampler sampler = sampler((x, z) -> x < 16 ? Optional.of(column(75, false)) : Optional.empty(),
                (x, z) -> { predicted.incrementAndGet(); return column(60, true); });
        assertEquals(76, sampler.terrainY(0, 0));
        assertEquals(0, predicted.get(), "existing saved/loaded terrain remains authoritative over the generator");
        assertEquals(61, sampler.terrainY(16, 0));
        assertEquals(60, sampler.waterSurfaceY(16, 0));
        assertEquals(41, sampler.oceanFloorY(16, 0));
        assertTrue(sampler.isWater(16, 60, 0));
        assertFalse(sampler.isWater(16, 40, 0));
        assertEquals(1, predicted.get(), "all height/fluid queries reuse one immutable predicted column");
        RoadTerrainSampler unavailable = sampler((x, z) -> Optional.empty(), (x, z) -> null);
        assertThrows(IllegalStateException.class, () -> unavailable.terrainY(900, 900),
                "unavailable data must wait/fail visibly, never become an invented flat or world-bottom road");
    }

    @Test
    void fullNbtSuppliesExactRoadWaterAndFoundationColumnsWithoutAWorld() throws Exception {
        CompoundTag chunk = new CompoundTag();
        chunk.putString("Status", "minecraft:full");
        ListTag sections = new ListTag();
        sections.add(section(0, "minecraft:stone"));
        sections.add(section(1, "minecraft:water"));
        chunk.put("sections", sections);
        RoadTerrainSampler sampler;
        try {
            sampler = (RoadTerrainSampler) type().getMethod("fromSavedChunk", CompoundTag.class,
                    int.class, int.class, int.class, int.class).invoke(null, chunk, -17, 31, 0, 128);
        } catch (NoSuchMethodException missing) {
            sampler = fail("long road previews need the same FULL saved terrain without holding world chunks loaded");
        }
        assertEquals(32, sampler.terrainY(-17 * 16 + 8, 31 * 16 + 8));
        assertEquals(31, sampler.waterSurfaceY(-17 * 16 + 8, 31 * 16 + 8));
        assertEquals(16, sampler.oceanFloorY(-17 * 16 + 8, 31 * 16 + 8));
    }

    private static RoadTerrainSampler sampler(BiFunction<Integer, Integer, Optional<NoiseColumn>> existing,
                                               GeneratorColumnPredictor.ColumnSource predicted) throws Exception {
        return (RoadTerrainSampler) type().getConstructor(BiFunction.class, GeneratorColumnPredictor.ColumnSource.class,
                int.class, int.class, int.class).newInstance(existing, predicted, 0, 128, 63);
    }

    private static Class<?> type() {
        try { return Class.forName("com.monpai.sailboatmod.roadplanner.structure.RoadPlanningTerrainSnapshot"); }
        catch (ClassNotFoundException missing) { return fail("road prediction must read owned FULL columns before native generator columns"); }
    }

    private static NoiseColumn column(int top, boolean water) {
        BlockState[] states = new BlockState[128];
        for (int y = 0; y < states.length; y++) states[y] = y > top ? Blocks.AIR.defaultBlockState()
                : water && y > 40 ? Blocks.WATER.defaultBlockState() : Blocks.STONE.defaultBlockState();
        return new NoiseColumn(0, states);
    }

    private static CompoundTag section(int y, String name) {
        CompoundTag section = new CompoundTag();
        section.putByte("Y", (byte) y);
        CompoundTag entry = new CompoundTag();
        entry.putString("Name", name);
        ListTag palette = new ListTag(); palette.add(entry);
        CompoundTag states = new CompoundTag(); states.put("palette", palette);
        section.put("block_states", states);
        return section;
    }
}
