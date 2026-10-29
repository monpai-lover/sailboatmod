package com.monpai.sailboatmod.route.terrain;

import net.minecraft.core.BlockPos;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** A straight corridor cannot revisit tiles once they leave its recent cross sections. */
final class LazyPrefetchCorridor implements Iterator<CompletableFuture<Void>> {
    private final PrefetchGate gate;
    private final BlockPos start;
    private final long dx, dz, steps;
    private final int side;
    private final LinkedHashSet<Long> recent = new LinkedHashSet<>();
    private long sample;
    private int tx, tz, minZ, maxX, maxZ;
    private boolean rectangle;
    private Long next;

    LazyPrefetchCorridor(PrefetchGate gate, BlockPos start, BlockPos end, int side) {
        if (side > 4096) throw new IllegalArgumentException("Prefetch corridor half-width exceeds 4096");
        this.gate = gate; this.start = start; this.side = Math.max(0, side);
        dx = (long) end.getX() - start.getX(); dz = (long) end.getZ() - start.getZ();
        steps = Math.max(1L, (long) Math.ceil(Math.hypot(dx, dz) / 256));
    }

    @Override public boolean hasNext() {
        if (next != null) return true;
        while (rectangle || sample <= steps) {
            if (!rectangle) {
                long x = start.getX() + Math.round(dx * (double) sample / steps);
                long z = start.getZ() + Math.round(dz * (double) sample / steps);
                sample++;
                tx = (int) Math.floorDiv(x - side, 512L); maxX = (int) Math.floorDiv(x + side, 512L);
                tz = minZ = (int) Math.floorDiv(z - side, 512L); maxZ = (int) Math.floorDiv(z + side, 512L);
                rectangle = true;
            }
            long key = (long) tx << 32 | (tz & 0xFFFFFFFFL);
            if (++tz > maxZ) { tz = minZ; if (++tx > maxX) rectangle = false; }
            if (recent.add(key)) {
                if (recent.size() > 1024) recent.remove(recent.iterator().next());
                next = key; return true;
            }
        }
        return false;
    }

    @Override public CompletableFuture<Void> next() {
        if (!hasNext()) throw new NoSuchElementException();
        long key = next; next = null;
        return gate.request(((int) (key >> 32)) * 32, ((int) key) * 32);
    }
}
