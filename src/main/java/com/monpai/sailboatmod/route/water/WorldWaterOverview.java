package com.monpai.sailboatmod.route.water;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * 内置的全球海陆概览(1/16°,由 tools/gen_world_water_overview.py 从 Natural Earth 1:10m 陆地生成,公有领域)。
 * 像素内只要有任一处是水就算水,海峡不会被粗化堵死。只给 Tellus 地球世界的远航全局搜索当<b>启发式</b>用
 * (估算绕开大陆的海路距离),可航与否永远由 Tellus 自己的陆地遮罩判定;所以它不需要下载任何东西。
 */
public final class WorldWaterOverview {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final String RESOURCE = "world_water_overview.bin.gz";
    /** Tellus EarthProjection 常量(Web Mercator)。 */
    private static final double METERS_PER_DEGREE = 111319.49166666667D;
    private static final double EARTH_RADIUS = 6378137.050041738D;

    private final int width, height, ppd;
    private final double maxLat;
    private final long[] bits;

    private WorldWaterOverview(int width, int height, int ppd, byte[] packed) {
        this.width = width;
        this.height = height;
        this.ppd = ppd;
        this.maxLat = height / 2.0D / ppd;
        int rowBytes = (width + 7) / 8;
        this.bits = new long[(int) (((long) width * height + 63) / 64)];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((packed[y * rowBytes + (x >> 3)] & (0x80 >> (x & 7))) != 0) {
                    long i = (long) y * width + x;
                    bits[(int) (i >> 6)] |= 1L << (i & 63);
                }
            }
        }
    }

    private static final class Holder {
        static final WorldWaterOverview INSTANCE = load();
    }

    /** null 时(资源缺失/损坏)调用方退回按地形源取样的概览。 */
    public static WorldWaterOverview get() {
        return Holder.INSTANCE;
    }

    private static WorldWaterOverview load() {
        try (InputStream raw = WorldWaterOverview.class.getResourceAsStream(RESOURCE)) {
            if (raw == null) throw new IOException("missing " + RESOURCE);
            DataInputStream in = new DataInputStream(new GZIPInputStream(raw));
            byte[] magic = in.readNBytes(4);
            if (magic.length != 4 || magic[0] != 'S' || magic[1] != 'B' || magic[2] != 'W' || magic[3] != 'W') {
                throw new IOException("bad magic");
            }
            int width = in.readInt(), height = in.readInt(), ppd = in.readInt();
            byte[] packed = in.readNBytes(((width + 7) / 8) * height);
            if (packed.length != ((width + 7) / 8) * height) throw new IOException("truncated");
            return new WorldWaterOverview(width, height, ppd, packed);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[WaterPath] 内置全球海陆概览载入失败,远航退回按地形源概览: {}", e.toString());
            return null;
        }
    }

    public boolean water(double lon, double lat) {
        return anyWater(lon, lat, lon, lat);
    }

    /** 经纬度矩形内是否有任一水像素。 */
    public boolean anyWater(double lonA, double latA, double lonB, double latB) {
        int x0 = px(Math.min(lonA, lonB)), x1 = px(Math.max(lonA, lonB));
        int y0 = py(Math.max(latA, latB)), y1 = py(Math.min(latA, latB));
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                long i = (long) y * width + x;
                if ((bits[(int) (i >> 6)] & (1L << (i & 63))) != 0) return true;
            }
        }
        return false;
    }

    private int px(double lon) {
        return Math.max(0, Math.min(width - 1, (int) Math.floor((lon + 180.0D) * ppd)));
    }

    private int py(double lat) {
        return Math.max(0, Math.min(height - 1, (int) Math.floor((maxLat - lat) * ppd)));
    }

    private boolean pixel(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return false;
        long i = (long) y * width + x;
        return (bits[(int) (i >> 6)] & (1L << (i & 63))) != 0;
    }

    /** 概览图格边长(像素):8 像素 = 0.5°。 */
    static final int GRAPH_CELL = 8;
    private static final int MAX_SETTLED = 300_000;

    /**
     * Tellus 世界的海路距离场:在内置概览的像素上做图,每个 8×8 像素格按<b>格内局部连通分量</b>拆成节点
     * (同一格里的两片互不相连的水 = 两个节点,所以不会像「格内有水就算水」那样从地峡漏过去),
     * 相邻格只有像素真的 8 邻接才连边,边长 = 两格中心在 Tellus 方块坐标里的距离。
     * 从 target 朝 other 跑 A*(一致启发),到 f &gt; 1.3×最优即停,只确定航线走廊。
     */
    public GlobalWaterGridPlanner.SeaDistance seaDistance(double worldScale, int targetX, int targetZ, int otherX, int otherZ) {
        return new Field(worldScale, targetX, targetZ, otherX, otherZ);
    }

    private final class Field implements GlobalWaterGridPlanner.SeaDistance {
        private final double scale;
        private final java.util.HashMap<Long, byte[]> labels = new java.util.HashMap<>();
        private final GlobalWaterGridPlanner.LongIntMap index = new GlobalWaterGridPlanner.LongIntMap();
        private double[] dist = new double[4096];
        private boolean[] closed = new boolean[4096];
        private int size, settled;

        Field(double scale, int tx, int tz, int ox, int oz) {
            this.scale = scale;
            long t = nodeAt(tx, tz), o = nodeAt(ox, oz);
            if (t < 0 || o < 0) return;
            double[] oc = {bx(cellCenterLon(cellX(o))), bz(cellCenterLat(cellY(o)))};
            double[] heapF = new double[4096];
            long[] heapK = new long[4096];
            int heap = 1;
            index.put(t, size);
            dist[size++] = 0;
            heapF[0] = h(t, oc);
            heapK[0] = t;
            double stopAt = Double.MAX_VALUE;
            while (heap > 0 && settled < MAX_SETTLED) {
                WaterRouteCancellation.check();
                double f = heapF[0];
                long k = heapK[0];
                heap--;
                double lf = heapF[heap];
                long lk = heapK[heap];
                int i = 0;
                while (true) {
                    int c = 2 * i + 1;
                    if (c >= heap) break;
                    if (c + 1 < heap && heapF[c + 1] < heapF[c]) c++;
                    if (heapF[c] >= lf) break;
                    heapF[i] = heapF[c];
                    heapK[i] = heapK[c];
                    i = c;
                }
                if (heap > 0) { heapF[i] = lf; heapK[i] = lk; }
                int n = index.get(k);
                if (closed[n] || f > dist[n] + h(k, oc) + 1.0E-6D) continue;
                closed[n] = true;
                settled++;
                if (dist[n] > stopAt) break;
                if (k == o) stopAt = dist[n] * 1.3D + 4 * GRAPH_CELL * 70.0D;
                for (long nk : neighbours(k)) {
                    double nd = dist[n] + between(k, nk);
                    int m = index.get(nk);
                    if (m >= 0 && dist[m] <= nd) continue;
                    if (m < 0) {
                        if (size == dist.length) {
                            dist = java.util.Arrays.copyOf(dist, size * 2);
                            closed = java.util.Arrays.copyOf(closed, size * 2);
                        }
                        m = size++;
                        index.put(nk, m);
                    }
                    dist[m] = nd;
                    double nf = nd + h(nk, oc);
                    if (heap == heapF.length) { heapF = java.util.Arrays.copyOf(heapF, heap * 2); heapK = java.util.Arrays.copyOf(heapK, heap * 2); }
                    int j = heap++;
                    while (j > 0) {
                        int p = (j - 1) >>> 1;
                        if (heapF[p] <= nf) break;
                        heapF[j] = heapF[p];
                        heapK[j] = heapK[p];
                        j = p;
                    }
                    heapF[j] = nf;
                    heapK[j] = nk;
                }
            }
        }

        @Override public int settled() { return settled; }

        @Override public double distance(int x, int z) {
            long node = nodeAt(x, z);
            if (node < 0) return -1;
            int n = index.get(node);
            return n < 0 || !closed[n] ? -1 : dist[n];
        }

        // node = ((cellX * 65536 + cellY) << 8) | localComponent
        private long node(int cx, int cy, int comp) { return (((long) cx << 16 | cy) << 8) | comp; }
        private int cellX(long node) { return (int) (node >>> 24); }
        private int cellY(long node) { return (int) ((node >>> 8) & 0xFFFF); }
        private int comp(long node) { return (int) (node & 0xFF); }

        private double cellCenterLon(int cx) { return (cx * GRAPH_CELL + GRAPH_CELL / 2.0D) / ppd - 180.0D; }
        private double cellCenterLat(int cy) { return maxLat - (cy * GRAPH_CELL + GRAPH_CELL / 2.0D) / ppd; }
        private double bx(double lon) { return lon * METERS_PER_DEGREE / scale; }
        private double bz(double lat) { return -EARTH_RADIUS * Math.log(Math.tan(Math.PI / 4 + Math.toRadians(lat) / 2)) / scale; }

        private double h(long node, double[] oc) {
            return Math.hypot(bx(cellCenterLon(cellX(node))) - oc[0], bz(cellCenterLat(cellY(node))) - oc[1]);
        }

        private double between(long a, long b) {
            return Math.hypot(bx(cellCenterLon(cellX(a))) - bx(cellCenterLon(cellX(b))),
                    bz(cellCenterLat(cellY(a))) - bz(cellCenterLat(cellY(b))));
        }

        /** 方块坐标所在像素(陆地像素则取 3×3 邻域里的水像素)对应的节点;找不到为 -1。 */
        private long nodeAt(int x, int z) {
            int px = px(lon(x + 0.5D, scale)), py = py(lat(z + 0.5D, scale));
            for (int r = 0; r <= 2; r++) {
                for (int dy = -r; dy <= r; dy++) for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                    int qx = px + dx, qy = py + dy;
                    if (pixel(qx, qy)) return node(qx / GRAPH_CELL, qy / GRAPH_CELL, label(qx / GRAPH_CELL, qy / GRAPH_CELL)[(qy % GRAPH_CELL) * GRAPH_CELL + qx % GRAPH_CELL]);
                }
            }
            return -1;
        }

        /** 格内 8 连通标号(1..n,陆地 0)。 */
        private byte[] label(int cx, int cy) {
            long key = (long) cx << 32 | cy;
            byte[] l = labels.get(key);
            if (l != null) return l;
            l = new byte[GRAPH_CELL * GRAPH_CELL];
            int next = 0;
            int[] stack = new int[GRAPH_CELL * GRAPH_CELL];
            for (int i = 0; i < l.length; i++) {
                if (l[i] != 0 || !pixel(cx * GRAPH_CELL + i % GRAPH_CELL, cy * GRAPH_CELL + i / GRAPH_CELL)) continue;
                next = Math.min(next + 1, 255);
                int sp = 0;
                stack[sp++] = i;
                l[i] = (byte) next;
                while (sp > 0) {
                    int c = stack[--sp];
                    int lx = c % GRAPH_CELL, ly = c / GRAPH_CELL;
                    for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                        int nx = lx + dx, ny = ly + dy;
                        if (nx < 0 || ny < 0 || nx >= GRAPH_CELL || ny >= GRAPH_CELL) continue;
                        int ni = ny * GRAPH_CELL + nx;
                        if (l[ni] != 0 || !pixel(cx * GRAPH_CELL + nx, cy * GRAPH_CELL + ny)) continue;
                        l[ni] = (byte) next;
                        stack[sp++] = ni;
                    }
                }
            }
            labels.put(key, l);
            return l;
        }

        private java.util.List<Long> neighbours(long node) {
            int cx = cellX(node), cy = cellY(node), comp = comp(node);
            byte[] own = label(cx, cy);
            java.util.LinkedHashSet<Long> out = new java.util.LinkedHashSet<>();
            for (int i = 0; i < own.length; i++) {
                if ((own[i] & 0xFF) != comp) continue;
                int lx = i % GRAPH_CELL, ly = i / GRAPH_CELL;
                if (lx != 0 && ly != 0 && lx != GRAPH_CELL - 1 && ly != GRAPH_CELL - 1) continue;
                int gx = cx * GRAPH_CELL + lx, gy = cy * GRAPH_CELL + ly;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int qx = gx + dx, qy = gy + dy;
                    if (qx < 0 || qy < 0 || qx >= width || qy >= height) continue;
                    int ncx = qx / GRAPH_CELL, ncy = qy / GRAPH_CELL;
                    if ((ncx == cx && ncy == cy) || !pixel(qx, qy)) continue;
                    out.add(node(ncx, ncy, label(ncx, ncy)[(qy % GRAPH_CELL) * GRAPH_CELL + qx % GRAPH_CELL] & 0xFF));
                }
            }
            return new java.util.ArrayList<>(out);
        }
    }

    /** Tellus 世界(worldScale = 每格米数)的海路距离场来源。 */
    public GlobalWaterGridPlanner.SeaDistanceSource seaDistances(double worldScale) {
        return (target, other) -> seaDistance(worldScale, target.getX(), target.getZ(), other.getX(), other.getZ());
    }

    /** Tellus 世界方块矩形 → 是否有水(worldScale = 每格米数)。 */
    public GlobalWaterGridPlanner.OverviewWater forTellusScale(double worldScale) {
        return (minX, minZ, maxX, maxZ) -> anyWater(lon(minX, worldScale), lat(maxZ, worldScale),
                lon(maxX, worldScale), lat(minZ, worldScale));
    }

    static double lon(double blockX, double worldScale) {
        return blockX * worldScale / METERS_PER_DEGREE;
    }

    static double lat(double blockZ, double worldScale) {
        return Math.toDegrees(Math.atan(Math.sinh(-blockZ * worldScale / EARTH_RADIUS)));
    }
}
