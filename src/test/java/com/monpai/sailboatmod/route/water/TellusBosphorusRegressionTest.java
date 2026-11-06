package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.route.water.tellus.PmTilesLandMask;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in regression against the official Tellus Land Polygons v1.0.0 mask, not synthetic terrain.
 * Set SAILBOAT_TELLUS_LANDMASK, then run --tests '*TellusBosphorusRegressionTest'.
 * Only the mask-backed planning pipeline is covered; this is not native Tellus or a saved-world test.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TellusBosphorusRegressionTest {
    private static final double SCALE = 100.0D;
    private static final int SEA_Y = 63;
    private static final long LIMIT_MILLIS = 120_000L;
    private static TellusMaskRouteFixture fixture;
    private static PmTilesLandMask mask;
    private static BlockPos constantinople;
    private static BlockPos sevastopol;

    @AfterAll
    static void closeFixture() throws Exception {
        TellusMaskRouteFixture finished = fixture;
        fixture = null;
        mask = null; // Alias of fixture.mask; close it only through its owner.
        constantinople = null;
        sevastopol = null;
        if (finished != null) finished.close();
    }

    @BeforeAll
    static void prepareOfficialMask() throws Exception {
        String file = System.getenv("SAILBOAT_TELLUS_LANDMASK");
        assumeTrue(file != null && Files.isRegularFile(Path.of(file)), "Tellus land mask not provided");
        fixture = new TellusMaskRouteFixture(Path.of(file), SCALE);
        mask = fixture.mask;
        constantinople = fixture.endpoint(28.97D, 40.99D);
        sevastopol = fixture.endpoint(33.5D, 44.6D);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        System.out.printf("[bosphorus] scale=%s zoom=%d constantinople=%s sevastopol=%s%n",
                SCALE, mask.zoom(), constantinople, sevastopol);
    }

    @Test
    @Order(1)
    @Timeout(60)
    void originalSixteenBlockGridCannotConnectTheActualBosphorus() throws Exception {
        // Invoke one exact production Grid, without allowing the public planner's finer-cell retries.
        // Reflection keeps this historical failure reproducible after the fallback floor is repaired.
        Constructor<GlobalWaterGridPlanner> constructor = GlobalWaterGridPlanner.class.getDeclaredConstructor(
                WaterRouteWorld.class, GlobalWaterGridPlanner.SeaDistanceSource.class, int.class);
        constructor.setAccessible(true);
        GlobalWaterGridPlanner planner = constructor.newInstance(maskWorld(), null, SEA_Y);
        Class<?> gridType = Class.forName(GlobalWaterGridPlanner.class.getName() + "$Grid");
        Constructor<?> gridConstructor = gridType.getDeclaredConstructor(GlobalWaterGridPlanner.class, int.class, int.class);
        gridConstructor.setAccessible(true);
        Object grid = gridConstructor.newInstance(planner, 16, GlobalWaterGridPlanner.MAX_EXPANSIONS);
        Method search = gridType.getDeclaredMethod("search", BlockPos.class, BlockPos.class);
        search.setAccessible(true);
        long began = System.nanoTime();
        assertNull(search.invoke(grid, constantinople, sevastopol), "The 16-block centres must reproduce the missed strait");
        Field failure = gridType.getDeclaredField("failure");
        failure.setAccessible(true);
        assertEquals(WaterRouteFailureReason.NO_WATER_PATH, failure.get(grid));
        Field expanded = GlobalWaterGridPlanner.class.getDeclaredField("expanded");
        expanded.setAccessible(true);
        System.out.printf("[bosphorus] historical cell=16 expanded=%d elapsed=%dms%n",
                expanded.getInt(planner), (System.nanoTime() - began) / 1_000_000L);
    }

    @Test
    @Order(2)
    @Timeout(300)
    void completeMaskBackedPlanningAndFinalHullValidationSucceedInBothDirections() throws Exception {
        for (boolean reverse : new boolean[]{false, true}) {
            BlockPos source = reverse ? sevastopol : constantinople;
            BlockPos target = reverse ? constantinople : sevastopol;
            long began = System.nanoTime();
            WaterRouteResult<List<BlockPos>> result = fixture.plan(source, target, LIMIT_MILLIS);
            System.out.printf("[bosphorus] reverse=%s successful=%s reason=%s elapsed=%dms%n", reverse,
                    result.successful(), result.reason(), (System.nanoTime() - began) / 1_000_000L);
            assertTrue(result.successful(), "reverse=" + reverse + " reason=" + result.reason());
            List<BlockPos> path = result.value();
            assertEquals(source, path.getFirst(), "the formal source berth must remain exact");
            assertEquals(target, path.getLast(), "the formal destination berth must remain exact");
            assertTrue(path.stream().anyMatch(p -> {
                double latitude = PmTilesLandMask.lat(p.getZ(), SCALE);
                return latitude >= 41.06D && latitude <= 41.20D;
            }), "route must actually traverse the Bosphorus");
            long hullChecks = fixture.assertHullWater(path);
            System.out.printf("[bosphorus] reverse=%s waypoints=%d independentHullChecks=%d%n", reverse, path.size(), hullChecks);
            String output = System.getenv("SAILBOAT_TELLUS_ROUTE_OUT");
            if (output != null) fixture.writeCsv(Path.of(output + (reverse ? "-bosphorus-reverse.csv" : "-bosphorus-forward.csv")), path);
        }
    }

    private static WaterRouteWorld maskWorld() {
        return new WaterRouteWorld() {
            @Override
            public WaterColumn sample(int x, int z, WaterRoutePolicy policy) {
                return mask.isLand(x, z, mask.zoomFor(Math.max(2, policy.stepSize()))) ? WaterColumn.blocked()
                        : WaterColumn.passable(new BlockPos(x, SEA_Y, z), 0);
            }

            @Override public boolean canLoadMoreChunks(int requested) { return false; }
            @Override public int consumedChunkLoads() { return 0; }
        };
    }

}
