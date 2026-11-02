package com.monpai.sailboatmod.route.water;

/** Exact cell supercover of a segment between cell centres; symmetric, including corner contacts. */
final class WaterGridTraversal {
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
            long decision = (2 * ix + 1) * nz - (2 * iz + 1) * nx;
            if (decision == 0) {
                if (!check.test(x + sx, z) || !check.test(x, z + sz)) return false;
                x += sx; z += sz; ix++; iz++;
            } else if (decision < 0) { x += sx; ix++; }
            else { z += sz; iz++; }
            if (!check.test(x, z)) return false;
        }
        return true;
    }
}
