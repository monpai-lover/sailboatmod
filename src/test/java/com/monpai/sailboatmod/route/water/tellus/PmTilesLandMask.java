package com.monpai.sailboatmod.route.water.tellus;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/**
 * Offline reader for Tellus' own land mask ({@code tellus_landmask.pmtiles}, the same file Tellus downloads at runtime).
 * Mirrors {@code TellusLandMaskSource}: raster PNG tiles, sample &gt; 0 = land, missing tile = water, zoom chosen from the
 * world scale, block coordinates projected with Tellus' Web-Mercator {@code EarthProjection}.
 */
public final class PmTilesLandMask {
    static final double METERS_PER_DEGREE = 111319.49166666667D;
    static final double EARTH_RADIUS = 6378137.050041738D;
    private static final double EQUATOR = 4.0075017E7D;

    private record Entry(long tileId, long offset, int length, int runLength) { }
    private record Tile(int width, int height, java.util.BitSet land) { }
    private static final Tile EMPTY_TILE = new Tile(0, 0, new java.util.BitSet());

    private final RandomAccessFile file;
    private final long tileDataOffset, leafDirOffset;
    private final int internalCompression;
    private final List<Entry> root;
    private final ConcurrentHashMap<Long, List<Entry>> leaves = new ConcurrentHashMap<>();
    private final java.util.Map<Long, Tile> tiles = new java.util.LinkedHashMap<>(256, 0.75F, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<Long, Tile> eldest) { return size() > 4096; }
    };
    private final double worldScale;
    private final int zoom, minZoom;

    public PmTilesLandMask(Path pmtiles, double worldScale) throws IOException {
        this.file = new RandomAccessFile(pmtiles.toFile(), "r");
        this.worldScale = worldScale;
        ByteBuffer h = ByteBuffer.wrap(read(0, 127)).order(ByteOrder.LITTLE_ENDIAN);
        if (h.get(7) != 3) throw new IOException("Unsupported PMTiles version " + h.get(7));
        long rootOff = h.getLong(8), rootLen = h.getLong(16);
        leafDirOffset = h.getLong(40);
        tileDataOffset = h.getLong(56);
        internalCompression = h.get(97);
        int minZoom = h.get(100), maxZoom = h.get(101);
        this.minZoom = minZoom;
        root = directory(read(rootOff, (int) rootLen));
        int z = (int) Math.round(Math.log(EQUATOR / (256.0D * worldScale)) / Math.log(2.0D));
        this.zoom = Math.max(minZoom, Math.min(maxZoom, z));
    }

    public int zoom() { return zoom; }
    public int minZoom() { return minZoom; }

    /** Simulated synchronous download per tile that carries data (Tellus fetches each tile with one HTTP range request). */
    private volatile long tileLatencyMillis;
    private final java.util.concurrent.atomic.AtomicLong simulatedWaitMillis = new java.util.concurrent.atomic.AtomicLong();
    public void simulateTileLatency(long millis) { tileLatencyMillis = millis; }
    public long simulatedWaitMillis() { return simulatedWaitMillis.get(); }

    public static double lon(double blockX, double scale) { return blockX * scale / METERS_PER_DEGREE; }
    public static double lat(double blockZ, double scale) { return Math.toDegrees(Math.atan(Math.sinh(-blockZ * scale / EARTH_RADIUS))); }
    public static double blockX(double lon, double scale) { return lon * METERS_PER_DEGREE / scale; }
    public static double blockZ(double lat, double scale) {
        return -EARTH_RADIUS * Math.log(Math.tan(Math.PI / 4 + Math.toRadians(lat) / 2)) / scale;
    }

    /** True when the Tellus land mask marks this block as land. */
    public boolean isLand(int blockX, int blockZ) {
        return isLand(blockX, blockZ, zoom);
    }

    /** Same zoom choice the production sampler makes: data pixel about {@code max(1, blocksPerSample / 2)} blocks. */
    public int zoomFor(int blocksPerSample) {
        int k = Math.max(1, blocksPerSample / 2);
        int z = (int) Math.round(Math.log(EQUATOR / (256.0D * worldScale * k)) / Math.log(2.0D));
        return Math.max(minZoom, Math.min(zoom, z));
    }

    public boolean isLand(int blockX, int blockZ, int zoom) {
        double lon = lon(blockX + 0.5D, worldScale), lat = lat(blockZ + 0.5D, worldScale);
        if (lat > 85.05112878D || lat < -85.05112878D) return true;
        lon = Math.max(-180.0D, Math.min(180.0D, lon));
        double n = Math.pow(2.0D, zoom);
        double px = (lon + 180.0D) / 360.0D * n;
        double latR = Math.toRadians(lat);
        double py = (1.0D - Math.log(Math.tan(latR) + 1.0D / Math.cos(latR)) / Math.PI) / 2.0D * n;
        int tx = (int) Math.floor(px), ty = (int) Math.floor(py);
        tx = Math.max(0, Math.min((int) n - 1, tx));
        ty = Math.max(0, Math.min((int) n - 1, ty));
        Tile tile = tile(zoom, tx, ty);
        if (tile.width == 0) return false;
        int ix = Math.max(0, Math.min(tile.width - 1, (int) Math.floor((px - tx) * tile.width)));
        int iy = Math.max(0, Math.min(tile.height - 1, (int) Math.floor((py - ty) * tile.height)));
        return tile.land.get(iy * tile.width + ix);
    }

    private synchronized Tile tile(int zoom, int x, int y) {
        long id = tileId(zoom, x, y);
        Tile cached = tiles.get(id);
        if (cached != null) return cached;
        Tile loaded = load(id);
        tiles.put(id, loaded);
        return loaded;
    }

    private final java.util.Set<Long> touched = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<Long> touchedWithData = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Distinct tiles looked up / tiles that actually carry data (absent tiles are open ocean, no download). */
    public String tileStats() {
        java.util.Map<Integer, int[]> byZoom = new java.util.TreeMap<>();
        for (long id : touched) byZoom.computeIfAbsent(zoomOf(id), z -> new int[2])[0]++;
        for (long id : touchedWithData) byZoom.computeIfAbsent(zoomOf(id), z -> new int[2])[1]++;
        StringBuilder sb = new StringBuilder(touched.size() + " tiles looked up, " + touchedWithData.size() + " with data;");
        byZoom.forEach((z, c) -> sb.append(" z").append(z).append('=').append(c[0]).append('/').append(c[1]));
        return sb.toString();
    }

    private static int zoomOf(long tileId) {
        int z = 0;
        long acc = 0;
        while (acc + (1L << (2 * z)) <= tileId) { acc += 1L << (2 * z); z++; }
        return z;
    }

    private Tile load(long k) {
        touched.add(k);
        {
            try {
                byte[] data = find(k);
                if (data == null || data.length == 0) return EMPTY_TILE;
                if (touchedWithData.add(k) && tileLatencyMillis > 0) {
                    simulatedWaitMillis.addAndGet(tileLatencyMillis);
                    try { Thread.sleep(tileLatencyMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
                if (image == null) throw new IOException("bad tile image");
                Raster raster = image.getRaster();
                int w = image.getWidth(), hh = image.getHeight();
                java.util.BitSet land = new java.util.BitSet(w * hh);
                for (int yy = 0; yy < hh; yy++) for (int xx = 0; xx < w; xx++) {
                    if (raster.getSample(xx, yy, 0) > 0) land.set(yy * w + xx);
                }
                return new Tile(w, hh, land);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }

    private byte[] find(long tileId) throws IOException {
        List<Entry> dir = root;
        for (int depth = 0; depth < 4; depth++) {
            Entry e = search(dir, tileId);
            if (e == null) return null;
            if (e.runLength > 0) return read(tileDataOffset + e.offset, e.length);
            long leafKey = e.offset;
            Entry leaf = e;
            dir = leaves.computeIfAbsent(leafKey, k -> {
                try { return directory(read(leafDirOffset + leaf.offset, leaf.length)); }
                catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
            });
        }
        return null;
    }

    private static Entry search(List<Entry> entries, long tileId) {
        int lo = 0, hi = entries.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long c = entries.get(mid).tileId;
            if (c < tileId) lo = mid + 1;
            else if (c > tileId) hi = mid - 1;
            else return entries.get(mid);
        }
        if (hi >= 0) {
            Entry e = entries.get(hi);
            if (e.runLength == 0) return e;
            if (tileId - e.tileId < e.runLength) return e;
        }
        return null;
    }

    private List<Entry> directory(byte[] compressed) throws IOException {
        byte[] raw = internalCompression == 2 ? gunzip(compressed) : compressed;
        int[] pos = {0};
        int n = (int) varint(raw, pos);
        long[] ids = new long[n];
        long last = 0;
        for (int i = 0; i < n; i++) { last += varint(raw, pos); ids[i] = last; }
        int[] runs = new int[n];
        for (int i = 0; i < n; i++) runs[i] = (int) varint(raw, pos);
        int[] lens = new int[n];
        for (int i = 0; i < n; i++) lens[i] = (int) varint(raw, pos);
        long[] offs = new long[n];
        for (int i = 0; i < n; i++) {
            long v = varint(raw, pos);
            offs[i] = v == 0 && i > 0 ? offs[i - 1] + lens[i - 1] : v - 1;
        }
        List<Entry> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new Entry(ids[i], offs[i], lens[i], runs[i]));
        return out;
    }

    private static long varint(byte[] b, int[] pos) {
        long result = 0;
        int shift = 0;
        while (true) {
            int v = b[pos[0]++] & 0xFF;
            result |= (long) (v & 0x7F) << shift;
            if ((v & 0x80) == 0) return result;
            shift += 7;
        }
    }

    private static byte[] gunzip(byte[] data) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data)); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    private synchronized byte[] read(long offset, int length) throws IOException {
        byte[] out = new byte[length];
        file.seek(offset);
        file.readFully(out);
        return out;
    }

    static long tileId(int z, int x, int y) {
        long acc = 0;
        for (int t = 0; t < z; t++) acc += 1L << (2 * t);
        long n = 1L << z;
        long rx, ry, d = 0, tx = x, ty = y;
        for (long s = n / 2; s > 0; s /= 2) {
            rx = (tx & s) > 0 ? 1 : 0;
            ry = (ty & s) > 0 ? 1 : 0;
            d += s * s * ((3 * rx) ^ ry);
            if (ry == 0) {
                if (rx == 1) { tx = n - 1 - tx; ty = n - 1 - ty; }
                long tmp = tx; tx = ty; ty = tmp;
            }
        }
        return acc + d;
    }
}
