package com.monpai.sailboatmod.route.water;

/** Exact cell supercover of a segment between cell centres; symmetric, including corner contacts. */
public final class WaterGridTraversal {
    @FunctionalInterface interface CellCheck { boolean test(int x, int z); }
    private WaterGridTraversal() { }
    static boolean all(int x0, int z0, int x1, int z1, CellCheck check) {
        long nx = Math.abs((long) x1 - x0), nz = Math.abs((long) z1 - z0);
        int sx = Integer.compare(x1, x0), sz = Integer.compare(z1, z0);
        long ix = 0, iz = 0;
        int x = x0, z = z0;
        if (!check.test(x, z)) return false;
        while (ix < nx || iz < nz) {
            WaterRouteCancellation.check();
            // Compare next vertical/horizontal boundary times without floating-point rounding.
            long decision = boundaryDecision(ix, iz, nx, nz);
            if (decision == 0) {
                if (!check.test(x + sx, z) || !check.test(x, z + sz)) return false;
                x += sx; z += sz; ix++; iz++;
            } else if (decision < 0) { x += sx; ix++; }
            else { z += sz; iz++; }
            if (!check.test(x, z)) return false;
        }
        return true;
    }

    private static long boundaryDecision(long ix, long iz, long nx, long nz) {
        return (2 * ix + 1) * nz - (2 * iz + 1) * nx;
    }

    /** Lazy primitive cursor for bounded lookahead; the fine-planner hot path above allocates no cursor. */
    public static Cursor cursor(int x0, int z0, int x1, int z1) {
        return new Cursor(x0, z0, x1, z1);
    }

    public static final class Cursor {
        private final long nx, nz;
        private final int sx, sz;
        private long ix, iz;
        private int x, z, previousX, previousZ;
        private boolean started, corner;

        private Cursor(int x0, int z0, int x1, int z1) {
            x = x0; z = z0;
            nx = Math.abs((long) x1 - x0); nz = Math.abs((long) z1 - z0);
            sx = Integer.compare(x1, x0); sz = Integer.compare(z1, z0);
        }

        public boolean hasNext() { return !started || ix < nx || iz < nz; }
        public boolean advance() {
            if (!hasNext()) return false;
            corner = false;
            if (!started) { started = true; return true; }
            WaterRouteCancellation.check();
            previousX = x; previousZ = z;
            long decision = boundaryDecision(ix, iz, nx, nz);
            if (decision == 0) {
                corner = true;
                x += sx; z += sz; ix++; iz++;
            } else if (decision < 0) { x += sx; ix++; }
            else { z += sz; iz++; }
            return true;
        }
        public int x() { return x; }
        public int z() { return z; }
        public boolean corner() { return corner; }
        public int previousX() { return previousX; }
        public int previousZ() { return previousZ; }
    }
}
