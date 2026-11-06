package com.monpai.sailboatmod.route.water.lookahead;

import com.monpai.sailboatmod.route.water.RealBlockWaterMap;
import com.monpai.sailboatmod.route.water.WaterGridTraversal;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongPredicate;

/**
 * 航行前瞻(纯逻辑):算前方要加载的走廊区块,并在<b>已加载</b>区块上逐格做 2×2 船宽校验找第一个障碍。
 * 航段 i = route[i] → route[i+1]。
 */
public final class RouteLookahead {
    /**
     * 障碍:航线从第 fromSegment 段的 before 格之后被挡,到第 toSegment 段的 after 格恢复可航。before/after 都在原航线的
     * 连线上、都通过 2×2 船宽校验;绕行段插在 route[fromSegment] 与 route[toSegment+1] 之间。
     */
    public record Obstacle(int fromSegment, BlockPos before, int toSegment, BlockPos after) {
    }

    /** checkedUpTo = 第一个尚未整段确认可通的航段。 */
    public record Scan(int checkedUpTo, Optional<Obstacle> obstacle) {
    }

    private RouteLookahead() {
    }

    /** 船位 → route[targetIndex..] 沿线 lookaheadBlocks 格内经过的区块,外扩 sideChunks,按远近顺序最多 cap 个。 */
    public static Set<Long> corridorChunks(List<Vec3> route, int targetIndex, Vec3 boat, int lookaheadBlocks,
                                           int sideChunks, int cap) {
        Set<Long> out = new LinkedHashSet<>();
        if (route == null || boat == null || cap <= 0) {
            return out;
        }
        double remaining = lookaheadBlocks;
        BlockPos from = cell(boat);
        for (int i = Math.max(0, targetIndex); i < route.size() && remaining > 0 && out.size() < cap; i++) {
            BlockPos to = cell(route.get(i));
            double length = Math.sqrt(from.distSqr(to));
            if (length > remaining) {
                double t = remaining / length;
                to = new BlockPos(Mth.floor(from.getX() + (to.getX() - from.getX()) * t), to.getY(),
                        Mth.floor(from.getZ() + (to.getZ() - from.getZ()) * t));
            }
            for (long chunk : segmentChunks(from, to)) {
                int cx = ChunkPos.getX(chunk);
                int cz = ChunkPos.getZ(chunk);
                for (int dx = -sideChunks; dx <= sideChunks && out.size() < cap; dx++) {
                    for (int dz = -sideChunks; dz <= sideChunks && out.size() < cap; dz++) {
                        out.add(ChunkPos.asLong(cx + dx, cz + dz));
                    }
                }
                if (out.size() >= cap) {
                    break;
                }
            }
            remaining -= length;
            from = to;
        }
        return out;
    }

    /**
     * 沿船前方逐格校验(从船正驶向目标点那段开始,该段从船的投影点起走)。只看<b>已加载</b>区块:遇到未加载就停,
     * 下一轮再接着看;走过 lookaheadBlocks 格也停。长航段也会被部分校验,不必整段都在走廊里。
     */
    public static Scan scan(List<Vec3> route, int targetIndex, Vec3 boat, int checkedUpTo, int lookaheadBlocks,
                            RealBlockWaterMap map, LongPredicate chunkLoaded, int halfWidth) {
        int current = Math.max(0, targetIndex - 1);
        int start = Math.max(checkedUpTo, current);
        int checked = start;
        double travelled = 0.0D;
        BlockPos lastClear = null;
        int lastClearSegment = -1;
        BlockPos runBefore = null;
        int runFromSegment = -1;
        for (int i = start; i + 1 < route.size(); i++) {
            BlockPos a = i == current && boat != null ? projection(boat, route.get(i), route.get(i + 1)) : flat(route.get(i));
            BlockPos b = flat(route.get(i + 1));
            double dx = (double) b.getX() - a.getX();
            double dz = (double) b.getZ() - a.getZ();
            double length = Math.hypot(dx, dz);
            for (Step step : walk(a, b)) {
                if (!stepLoaded(step, chunkLoaded)) {
                    return new Scan(checked, Optional.empty());
                }
                // Exact cell traversal has separate X/Z boundary events. Measure progress along
                // the actual leg rather than adding the zigzag distances between cell centres.
                double along = length == 0 ? 0 : Mth.clamp(((step.cell().getX() - a.getX()) * dx
                        + (step.cell().getZ() - a.getZ()) * dz) / length, 0, length);
                if (travelled + along > lookaheadBlocks) {
                    return new Scan(checked, Optional.empty());
                }
                boolean cellClear = map.hullClear(step.cell().getX(), step.cell().getZ(), halfWidth);
                if (runBefore == null) {
                    if (cellClear && sidesClear(step, map, halfWidth)) {
                        lastClear = step.cell();
                        lastClearSegment = i;
                    } else if (lastClear == null) {
                        return new Scan(checked, Optional.empty()); // 船前方第一格就不通:交给被动脱困
                    } else {
                        runBefore = lastClear;
                        runFromSegment = lastClearSegment;
                    }
                } else if (cellClear) {
                    return new Scan(checked, Optional.of(new Obstacle(runFromSegment, at(runBefore, route.get(runFromSegment)),
                            i, at(step.cell(), route.get(i)))));
                }
            }
            if (runBefore == null) {
                checked = i + 1;
            }
            travelled += length;
        }
        return new Scan(checked, Optional.empty());
    }

    /** 一步:主格 + 对角穿角时两侧被触及的格(supercover)。 */
    private record Step(BlockPos cell, BlockPos sideA, BlockPos sideB) {
    }

    /** 惰性 a→b supercover;前瞻停止时不再为长航段后方未访问的格分配对象。 */
    private static Iterable<Step> walk(BlockPos a, BlockPos b) {
        return () -> new Iterator<>() {
            private final WaterGridTraversal.Cursor cursor =
                    WaterGridTraversal.cursor(a.getX(), a.getZ(), b.getX(), b.getZ());

            @Override
            public boolean hasNext() {
                return cursor.hasNext();
            }

            @Override
            public Step next() {
                if (!cursor.advance()) {
                    throw new NoSuchElementException();
                }
                return new Step(new BlockPos(cursor.x(), 0, cursor.z()),
                        cursor.corner() ? new BlockPos(cursor.x(), 0, cursor.previousZ()) : null,
                        cursor.corner() ? new BlockPos(cursor.previousX(), 0, cursor.z()) : null);
            }
        };
    }

    private static boolean stepLoaded(Step step, LongPredicate chunkLoaded) {
        Set<Long> chunks = new LinkedHashSet<>();
        addHull(chunks, step.cell().getX(), step.cell().getZ());
        if (step.sideA() != null) {
            addHull(chunks, step.sideA().getX(), step.sideA().getZ());
            addHull(chunks, step.sideB().getX(), step.sideB().getZ());
        }
        return allLoaded(chunks, chunkLoaded);
    }

    private static boolean sidesClear(Step step, RealBlockWaterMap map, int halfWidth) {
        return step.sideA() == null
                || (map.hullClear(step.sideA().getX(), step.sideA().getZ(), halfWidth)
                && map.hullClear(step.sideB().getX(), step.sideB().getZ(), halfWidth));
    }

    /** Recheck a previously found local obstruction using loaded snapshots only. */
    static boolean clearLoadedSegment(BlockPos a, BlockPos b, RealBlockWaterMap map,
                                      LongPredicate chunkLoaded, int halfWidth) {
        if (Math.hypot((double) b.getX() - a.getX(), (double) b.getZ() - a.getZ())
                > RouteLookaheadGuard.LOOKAHEAD_BLOCKS) return false;
        for (Step step : walk(a, b)) {
            if (!stepLoaded(step, chunkLoaded)
                    || !map.hullClear(step.cell().getX(), step.cell().getZ(), halfWidth)
                    || !sidesClear(step, map, halfWidth)) return false;
        }
        return true;
    }

    /** 船在 a→b 上的投影格(夹在端点之间)。 */
    private static BlockPos projection(Vec3 boat, Vec3 a, Vec3 b) {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        double lengthSq = dx * dx + dz * dz;
        double t = lengthSq <= 1.0E-9D ? 0.0D : Mth.clamp(((boat.x - a.x) * dx + (boat.z - a.z) * dz) / lengthSq, 0.0D, 1.0D);
        return new BlockPos(Mth.floor(a.x + dx * t), 0, Mth.floor(a.z + dz * t));
    }

    private static BlockPos flat(Vec3 v) {
        return new BlockPos(Mth.floor(v.x), 0, Mth.floor(v.z));
    }

    /** 走格时 Y 记 0;对外给出时套上航点的 Y(海平面)。 */
    private static BlockPos at(BlockPos flatCell, Vec3 reference) {
        return new BlockPos(flatCell.getX(), Mth.floor(reference.y), flatCell.getZ());
    }

    /** a→b supercover 经过的格(含 2×2 船宽的 +1 格)所在区块。 */
    static Set<Long> segmentChunks(BlockPos a, BlockPos b) {
        Set<Long> out = new LinkedHashSet<>();
        for (Step step : walk(a, b)) {
            addHull(out, step.cell().getX(), step.cell().getZ());
            if (step.sideA() != null) {
                addHull(out, step.sideA().getX(), step.sideA().getZ());
                addHull(out, step.sideB().getX(), step.sideB().getZ());
            }
        }
        return out;
    }

    private static void addHull(Set<Long> out, int x, int z) {
        out.add(ChunkPos.asLong(x >> 4, z >> 4));
        out.add(ChunkPos.asLong((x + 1) >> 4, z >> 4));
        out.add(ChunkPos.asLong(x >> 4, (z + 1) >> 4));
        out.add(ChunkPos.asLong((x + 1) >> 4, (z + 1) >> 4));
    }

    private static boolean allLoaded(Set<Long> chunks, LongPredicate chunkLoaded) {
        for (long chunk : chunks) {
            if (!chunkLoaded.test(chunk)) {
                return false;
            }
        }
        return true;
    }

    static BlockPos cell(Vec3 v) {
        return new BlockPos(Mth.floor(v.x), Mth.floor(v.y), Mth.floor(v.z));
    }
}
