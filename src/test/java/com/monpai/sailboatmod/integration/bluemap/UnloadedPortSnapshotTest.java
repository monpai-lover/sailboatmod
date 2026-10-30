package com.monpai.sailboatmod.integration.bluemap;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class UnloadedPortSnapshotTest {
    @Test void pruningRetainsUnloadedPortsWithoutInspectingTheirBlockEntities() {
        BlockPos unloaded = new BlockPos(10000, 64, 10000), destroyed = BlockPos.ZERO;
        var snapshots = List.of(new BlueMapMarkerSavedData.DockSnapshot("minecraft:overworld", unloaded, "remote", "", "", List.of()),
                new BlueMapMarkerSavedData.DockSnapshot("minecraft:overworld", destroyed, "destroyed", "", "", List.of()));
        List<BlockPos> removed = new ArrayList<>();
        AtomicInteger inspected = new AtomicInteger();
        BlueMapIntegration.pruneDockSnapshots(snapshots, pos -> !pos.equals(unloaded), pos -> {
            inspected.incrementAndGet();
            assertNotEquals(unloaded, pos, "an unloaded persisted port must never cause a block entity lookup");
            return false;
        }, removed::add);
        assertEquals(List.of(destroyed), removed);
        assertEquals(1, inspected.get());
    }
}
