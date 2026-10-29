package com.monpai.sailboatmod.transport;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

class SweptTerrainProbeTest {
    private Object newProbe() throws Exception {
        return Class.forName("com.monpai.sailboatmod.transport.control.SweptTerrainProbe")
                .getConstructor()
                .newInstance();
    }

    private String scan(
            Object probe,
            AABB bounds,
            int budget,
            Predicate<BlockPos> loaded,
            Predicate<BlockPos> collision)
            throws Exception {
        return probe.getClass()
                .getMethod("scan", AABB.class, int.class, Predicate.class, Predicate.class)
                .invoke(probe, bounds, budget, loaded, collision)
                .toString();
    }

    @Test
    void singleColumnObstacleInsideLargeHullCannotBeSkipped() throws Exception {
        Object probe = newProbe();
        String result = "WAITING";
        for (int i = 0; i < 100 && result.equals("WAITING"); i++)
            result =
                    scan(
                            probe,
                            new AABB(0, 0, 0, 30, 5, 30),
                            512,
                            p -> true,
                            p -> p.equals(new BlockPos(1, 1, 1)));
        assertEquals("BLOCKED", result);
    }

    @Test
    void budgetMustCompleteAllCellsBeforeDeclaringClear() throws Exception {
        Object probe = newProbe();
        AABB bounds = new AABB(0, 0, 0, 3, 1, 1);
        assertEquals("WAITING", scan(probe, bounds, 1, p -> true, p -> false));
        assertEquals("WAITING", scan(probe, bounds, 1, p -> true, p -> false));
        assertEquals("CLEAR", scan(probe, bounds, 1, p -> true, p -> false));
    }

    @Test
    void unknownTerrainRemainsWaitingUntilItIsActuallyLoaded() throws Exception {
        Object probe = newProbe();
        AtomicBoolean loaded = new AtomicBoolean(false);
        AABB bounds = new AABB(0, 0, 0, 1, 1, 1);
        assertEquals("WAITING", scan(probe, bounds, 10, p -> loaded.get(), p -> false));
        loaded.set(true);
        assertEquals("CLEAR", scan(probe, bounds, 10, p -> loaded.get(), p -> false));
    }

    @Test
    void changedWorldBoundsInvalidatePreviousAllClear() throws Exception {
        Object probe = newProbe();
        assertEquals("CLEAR", scan(probe, new AABB(0, 0, 0, 1, 1, 1), 10, p -> true, p -> false));
        assertEquals(
                "BLOCKED",
                scan(probe, new AABB(0, 0, 0, 4, 1, 1), 10, p -> true, p -> p.getX() == 3));
    }
}
