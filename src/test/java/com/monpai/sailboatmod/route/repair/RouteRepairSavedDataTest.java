package com.monpai.sailboatmod.route.repair;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteRepairSavedDataTest {
    private static final String DIM = "minecraft:overworld";
    private static final BlockPos DOCK = new BlockPos(10, 64, -20);

    @Test
    void roundTripsThroughNbt() {
        RouteRepairSavedData data = new RouteRepairSavedData();
        RoutePatch patch = patch(1);
        data.enqueue(DIM, DOCK, patch);

        RouteRepairSavedData loaded = RouteRepairSavedData.load(data.save(new CompoundTag()));

        assertEquals(List.of(patch), loaded.drain(DIM, DOCK));
    }

    @Test
    void drainRemovesEntries() {
        RouteRepairSavedData data = new RouteRepairSavedData();
        data.enqueue(DIM, DOCK, patch(1));

        assertEquals(1, data.drain(DIM, DOCK).size());
        assertTrue(data.drain(DIM, DOCK).isEmpty());
        assertTrue(data.drain(DIM, DOCK.above()).isEmpty());
    }

    @Test
    void capsAt32PerDock() {
        RouteRepairSavedData data = new RouteRepairSavedData();
        for (int i = 0; i < 40; i++) {
            data.enqueue(DIM, DOCK, patch(i));
        }

        List<RoutePatch> drained = data.drain(DIM, DOCK);

        assertEquals(RouteRepairSavedData.MAX_PATCHES_PER_DOCK, drained.size());
        assertEquals(patch(8), drained.get(0), "the oldest patches are dropped first");
        assertEquals(patch(39), drained.get(31));
    }

    private static RoutePatch patch(int seed) {
        Vec3 a = new Vec3(seed + 0.5D, 63, 0.25D);
        Vec3 b = new Vec3(seed + 10.5D, 63, -7.75D);
        return new RoutePatch(List.of(a, b), List.of(a, new Vec3(seed + 5.5D, 63.5D, 12.125D), b));
    }
}
