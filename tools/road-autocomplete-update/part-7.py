EDITS = [
('src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerAutoCompleteExecutorTest.java', None, 'b80ad72f36aae23a2e4854765b77cbf1bf87d4a4303d50f747e77eb5ef365537', [
(0, 0, r"""package com.monpai.sailboatmod.client.roadplanner;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RoadPlannerAutoCompleteExecutorTest {
    @Test
    void workRunsOffTheCallerThreadAndANewerRequestSupersedesTheOlderOne() throws Exception {
        UUID player = UUID.randomUUID();
        Thread caller = Thread.currentThread();
        List<String> delivered = new CopyOnWriteArrayList<>();
        List<Thread> workers = new CopyOnWriteArrayList<>();
        CountDownLatch slowStarted = new CountDownLatch(1), done = new CountDownLatch(1);
        RoadPlannerAutoCompleteExecutor.submit(player, () -> {
            workers.add(Thread.currentThread());
            slowStarted.countDown();
            long end = System.nanoTime() + 5_000_000_000L;
            while (System.nanoTime() < end && !Thread.currentThread().isInterrupted()) Thread.onSpinWait();
            return "old";
        }, Runnable::run, delivered::add, () -> fail("busy"));
        assertTrue(slowStarted.await(5, TimeUnit.SECONDS));
        RoadPlannerAutoCompleteExecutor.submit(player, () -> { workers.add(Thread.currentThread()); return "new"; },
                Runnable::run, r -> { delivered.add(r); done.countDown(); }, () -> fail("busy"));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);
        assertEquals(List.of("new"), delivered, "only the latest request is delivered; the old one is interrupted");
        assertFalse(workers.contains(caller), "pathfinding never runs on the calling (server) thread");
    }

    @Test
    void requestsFromDifferentPlayersAreIndependent() throws Exception {
        CountDownLatch done = new CountDownLatch(2);
        List<String> delivered = new CopyOnWriteArrayList<>();
        for (String name : List.of("a", "b")) {
            RoadPlannerAutoCompleteExecutor.submit(UUID.randomUUID(), () -> name, Runnable::run,
                    r -> { delivered.add(r); done.countDown(); }, () -> fail("busy"));
        }
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(2, delivered.size());
    }
}
""")
]),
('src/test/java/com/monpai/sailboatmod/road/pathfinding/FarTownAutoCompleteStressTest.java', None, 'e24c34a87fe43286df3a616639da03807259b8c156fe933ccc5b0efbee805530', [
(0, 0, r"""package com.monpai.sailboatmod.road.pathfinding;

import com.monpai.sailboatmod.client.roadplanner.LongRangeRoadPathfinder;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerAutoCompleteResult;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerAutoCompleteService;
import com.monpai.sailboatmod.road.config.PathfindingConfig;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Road auto-complete between far-apart towns: terminates within its budget and produces a usable route. */
class FarTownAutoCompleteStressTest {
    private static final long BUDGET_MILLIS = 45_000L;

    @Test
    void farTownsCompleteWithinBudgetAndAvoidTheInlandSea() {
        // distance, sea min x, sea max x, sea half width
        int[][] cases = {{5_000, 2_000, 2_600, 1_500}, {-7_000, -4200, -3600, 1500}, {20_000, 8_000, 12_000, 6_000}, {50_000, 20_000, 30_000, 12_000}};
        for (int[] c : cases) {
            long micros = Long.parseLong(java.util.Objects.requireNonNullElse(System.getenv("SAILBOAT_ROAD_SAMPLE_MICROS"), "0"));
            if (micros > 0 && c[0] < 50_000) continue; // slow mode: only the far case
            FarTownRoadTerrain terrain = new FarTownRoadTerrain(c[1], c[2], c[3], micros);
            PathfindingConfig config = new PathfindingConfig();
            config.setAlgorithm(PathfindingConfig.Algorithm.POTENTIAL_FIELD);
            LongRangeRoadPathfinder pathfinder = new LongRangeRoadPathfinder(config);
            RoadPlannerAutoCompleteService service = new RoadPlannerAutoCompleteService(
                    (from, to) -> pathfinder.findPath(from, to, terrain, BUDGET_MILLIS).path());
            BlockPos a = new BlockPos(0, 72, 0), b = new BlockPos(c[0], 72, 0);
            long t0 = System.nanoTime();
            LongRangeRoadPathfinder.Result raw = pathfinder.findPath(a, b, terrain, BUDGET_MILLIS);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            long seaNodes = raw.path().stream().filter(p -> terrain.isWater(p.getX(), p.getZ())).count();
            System.out.printf("[road-stress] distance=%d nodes=%d coarseStep=%d windows=%d refined=%d deadline=%s time=%dms"
                            + " getBaseHeightCalls=%d seaNodes=%d%n", c[0], raw.path().size(), raw.coarseStep(), raw.windows(),
                    raw.refinedWindows(), raw.deadlineHit(), ms, terrain.baseHeightCalls.get(), seaNodes);
            if (micros > 0 && raw.deadlineHit() && raw.path().isEmpty()) {
                assertTrue(ms < BUDGET_MILLIS + 15000, "slow terrain aborts without inventing a complete route");
                continue;
            }
            assertTrue(raw.path().size() >= 2, "distance " + c[0] + " found no route");
            assertEquals(a, raw.path().getFirst());
            assertEquals(b.getX(), raw.path().getLast().getX());
            assertEquals(0, seaNodes, "the road goes around the inland sea");
            long checked = 0;
            double length = 0;
            StringBuilder csv = new StringBuilder("x,y,z\n");
            for (BlockPos p : raw.path()) csv.append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ()).append('\n');
            for (int i=1; i<raw.path().size(); i++) {
                BlockPos u=raw.path().get(i-1), v=raw.path().get(i);
                double d=Math.hypot(v.getX()-(double)u.getX(), v.getZ()-(double)u.getZ()); length+=d;
                int n=Math.max(1,(int)Math.ceil(d/2));
                for(int j=0;j<=n;j++) {
                    int x=(int)Math.round(u.getX()+(v.getX()-(double)u.getX())*j/n);
                    int z=(int)Math.round(u.getZ()+(v.getZ()-(double)u.getZ())*j/n);
                    assertFalse(terrain.isWater(x,z), "edge crosses inland sea at " + x + "," + z); checked++;
                }
            }
            try {
                var output=java.nio.file.Path.of("build/road-evidence"); java.nio.file.Files.createDirectories(output);
                java.nio.file.Files.writeString(output.resolve("road-"+c[0]+".csv"),csv);
                java.nio.file.Files.writeString(output.resolve("road-"+c[0]+".txt"),
                        "synthetic=true length="+length+" elapsedMs="+ms+" edgeSamples="+checked+" waterHits=0\n");
            } catch(java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
            assertTrue(ms < BUDGET_MILLIS + 15_000, "bounded by the budget");
            if (micros > 0) continue;
            RoadPlannerAutoCompleteResult result = service.complete(a, b, List.of(), 24);
            assertTrue(result.success());
        }
    }
}
""")
])
]
