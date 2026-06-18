package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaterRouteTaskServiceTest {
    // 异步化后寻路在 WaterRouteTaskExecutor 后台跑。测试注入同步执行器(Runnable::run)保持确定性:
    // 首次 advance 提交→同步跑完→asyncResult 立即 set,下一 tick 的 advance poll 到结果 complete。
    @BeforeEach
    void useSyncExecutor() {
        WaterRouteTaskExecutor.setExecutorForTesting(Runnable::run);
    }

    @AfterEach
    void restoreExecutor() {
        WaterRouteTaskExecutor.setExecutorForTesting(null);
    }
    @Test
    void duplicateSourceTargetTaskIsRejected() {
        WaterRouteTaskService service = new WaterRouteTaskService();
        WaterRouteTask task = task(new BlockPos(0, 64, 0), new BlockPos(64, 64, 0), result -> {});

        assertTrue(service.submit(task).successful());
        WaterRouteResult<WaterRouteTask> duplicate = service.submit(task(new BlockPos(0, 64, 0), new BlockPos(64, 64, 0), result -> {}));

        assertEquals(WaterRouteFailureReason.ALREADY_PENDING, duplicate.reason());
    }

    @Test
    void taskAdvancesAcrossTicksAndCompletesOnce() {
        WaterRouteTaskService service = new WaterRouteTaskService();
        AtomicInteger completions = new AtomicInteger();
        AtomicReference<WaterRouteResult<List<BlockPos>>> completion = new AtomicReference<>();
        WaterRouteTask task = task(new BlockPos(0, 64, 0), new BlockPos(96, 64, 0), result -> {
            completions.incrementAndGet();
            completion.set(result);
        });

        service.submit(task);
        for (int i = 0; i < 100 && service.pendingCount() > 0; i++) {
            service.tick();
        }
        service.tick();

        assertEquals(0, service.pendingCount());
        assertEquals(1, completions.get());
        assertTrue(completion.get().successful());
        assertEquals(new BlockPos(0, 64, 0), completion.get().value().get(0));
        assertEquals(new BlockPos(96, 64, 0), completion.get().value().get(completion.get().value().size() - 1));
    }

    @Test
    void unreachableTargetCompletesWithFailure() {
        // 异步化后超时改 wall-clock,在同步测试执行器下不可靠;改测「目标不可达 → 失败完成」(语义等价:
        // 寻路跑不通也必须 complete 并从 pending 移除,不会永久挂起)。目标 (200,*) 在水域 0..128 外。
        WaterRouteTaskService service = new WaterRouteTaskService();
        AtomicReference<WaterRouteResult<List<BlockPos>>> completion = new AtomicReference<>();
        WaterRoutePolicy policy = new WaterRoutePolicy(8, 2, 1, 2, 256, 5000, 512, 64, 64, 200);
        WaterRoutePathfinder pathfinder = new WaterRoutePathfinder(
                new TestWaterWorld().waterRect(0, -16, 128, 16),
                new BlockPos(0, 64, 0),
                new BlockPos(200, 64, 0),
                policy);
        WaterRouteTask task = new WaterRouteTask(
                "minecraft:overworld",
                new BlockPos(0, 64, 0),
                new BlockPos(200, 64, 0),
                new BlockPos(0, 64, 0),
                new BlockPos(200, 64, 0),
                "Tester",
                policy,
                pathfinder,
                completion::set);

        service.submit(task);
        for (int i = 0; i < 10 && service.pendingCount() > 0; i++) {
            service.tick();
        }

        assertFalse(completion.get().successful());
        assertEquals(0, service.pendingCount());
    }

    @Test
    void skippedDimensionTasksDoNotConsumePerTickTaskBudget() {
        WaterRouteTaskService service = new WaterRouteTaskService(1);
        AtomicInteger overworldCompletions = new AtomicInteger();
        WaterRouteTask netherTask = task(
                "minecraft:the_nether",
                new BlockPos(0, 64, 0),
                new BlockPos(0, 64, 0),
                result -> {});
        WaterRouteTask overworldTask = task(
                "minecraft:overworld",
                new BlockPos(0, 64, 16),
                new BlockPos(0, 64, 16),
                result -> overworldCompletions.incrementAndGet());

        service.submit(netherTask);
        service.submit(overworldTask);
        service.tick("minecraft:overworld");

        assertEquals(1, overworldCompletions.get());
    }

    private static WaterRouteTask task(BlockPos source, BlockPos target, WaterRouteTask.CompletionHandler callback) {
        return task("minecraft:overworld", source, target, callback);
    }

    private static WaterRouteTask task(String dimensionId, BlockPos source, BlockPos target, WaterRouteTask.CompletionHandler callback) {
        WaterRoutePolicy policy = new WaterRoutePolicy(8, 2, 1, 2, 256, 5000, 512, 1, 64, 200);
        WaterRoutePathfinder pathfinder = new WaterRoutePathfinder(
                new TestWaterWorld().waterRect(0, -16, 128, 16),
                source,
                target,
                policy);
        return new WaterRouteTask(
                dimensionId,
                source,
                target,
                source,
                target,
                "Tester",
                policy,
                pathfinder,
                callback);
    }

    private static final class TestWaterWorld implements WaterRouteWorld {
        private final Set<Long> water = new HashSet<>();

        TestWaterWorld waterRect(int minX, int minZ, int maxX, int maxZ) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    water.add(key(x, z));
                }
            }
            return this;
        }

        @Override
        public WaterColumn sample(int x, int z, WaterRoutePolicy policy) {
            return water.contains(key(x, z))
                    ? WaterColumn.passable(new BlockPos(x, 64, z), 0.0D)
                    : WaterColumn.blocked();
        }

        @Override
        public boolean canLoadMoreChunks(int requested) {
            return true;
        }

        @Override
        public int consumedChunkLoads() {
            return 0;
        }

        private static long key(int x, int z) {
            return (((long) x) << 32) ^ (z & 0xffffffffL);
        }
    }
}
