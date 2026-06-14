package com.monpai.sailboatmod.client.roadplanner;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import com.monpai.sailboatmod.roadplanner.map.RoadMapTileSpec;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;

public class RoadPlannerTile implements AutoCloseable {
    public static final int TILE_SIZE_BLOCKS = 256;
    public static final int TILE_PIXEL_SIZE = 256;

    private final RoadPlannerTileKey key;
    private NativeImage image;
    private DynamicTexture texture;
    private ResourceLocation textureId;
    private long lastAccessedAt;
    private boolean loadedFromCache;
    private volatile boolean dirty;

    public RoadPlannerTile(RoadPlannerTileKey key) {
        this.key = key;
    }

    public RoadPlannerTileKey key() {
        return key;
    }

    public ResourceLocation textureId() {
        return textureId;
    }

    public long lastAccessedAt() {
        return lastAccessedAt;
    }

    public void loadOrCreate(File file) {
        closeTextureOnly();
        try {
            if (file.exists()) {
                image = NativeImage.read(Files.readAllBytes(file.toPath()));
                loadedFromCache = !looksLikePlaceholder(image);
                if (!loadedFromCache) {
                    image.close();
                    image = createLoadingImage();
                }
            } else {
                image = createLoadingImage();
                loadedFromCache = false;
            }
        } catch (IOException ignored) {
            image = createLoadingImage();
            loadedFromCache = false;
        }
        uploadTexture();
        markAccessed();
    }

    public void saveToFile(File file) {
        if (image == null) {
            return;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try {
            image.writeToFile(file);
            loadedFromCache = true;
        } catch (IOException ignored) {
        }
    }

    public boolean loadedFromCache() {
        return loadedFromCache;
    }

    public boolean isLoadingImage() {
        return !loadedFromCache;
    }

    public synchronized int[] copyPixels() {
        if (image == null || !loadedFromCache) {
            return new int[0];
        }
        int[] nativePixels = tilePixels();
        int[] argbPixels = new int[nativePixels.length];
        for (int index = 0; index < nativePixels.length; index++) {
            argbPixels[index] = toNativeAbgr(nativePixels[index]);
        }
        return argbPixels;
    }

    public void updateChunkDirect(RoadPlannerChunkImage chunkImage, int chunkXInTile, int chunkZInTile) {
        if (image == null || chunkImage == null || !chunkImage.isMeaningful()) {
            return;
        }
        NativeImage chunk = chunkImage.image();
        int startX = chunkXInTile * 16;
        int startZ = chunkZInTile * 16;
        int[] current = tilePixels();
        boolean applied = RoadPlannerTileMergeRules.mergeSubregion(current, TILE_PIXEL_SIZE, TILE_PIXEL_SIZE,
                chunkPixels(chunk), 16, 16, startX, startZ);
        if (!applied) {
            return;
        }
        writeTilePixels(current);
        if (texture != null) {
            texture.upload();
        }
        loadedFromCache = true;
    }

    public synchronized void updateChunk(RoadPlannerChunkImage chunkImage, int chunkXInTile, int chunkZInTile) {
        if (image == null || chunkImage == null || !chunkImage.isMeaningful()) {
            return;
        }
        NativeImage chunk = chunkImage.image();
        int startX = chunkXInTile * 16;
        int startZ = chunkZInTile * 16;
        int[] current = tilePixels();
        boolean applied = RoadPlannerTileMergeRules.mergeSubregion(current, TILE_PIXEL_SIZE, TILE_PIXEL_SIZE,
                chunkPixels(chunk), 16, 16, startX, startZ);
        if (!applied) {
            return;
        }
        writeTilePixels(current);
        dirty = true;
    }

    public synchronized void updatePixel(int localX, int localZ, int argb) {
        if (image == null) {
            return;
        }
        if (localX < 0 || localX >= TILE_PIXEL_SIZE || localZ < 0 || localZ >= TILE_PIXEL_SIZE) {
            return;
        }
        // 入参为项目 ARGB；NativeImage 内部用 native ABGR，写入前转换避免红蓝颠倒(蓝水变橙)。
        image.setPixelRGBA(localX, localZ, toNativeAbgr(argb));
        loadedFromCache = true;
        dirty = true;
    }

    public synchronized void replacePixels(int[] argbPixels) {
        if (image == null || argbPixels == null || argbPixels.length != RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS) {
            return;
        }
        for (int y = 0; y < RoadMapTileSpec.TILE_PIXELS; y++) {
            for (int x = 0; x < RoadMapTileSpec.TILE_PIXELS; x++) {
                image.setPixelRGBA(x, y, toNativeAbgr(argbPixels[y * RoadMapTileSpec.TILE_PIXELS + x]));
            }
        }
        loadedFromCache = true;
        dirty = true;
        if (texture != null) {
            texture.upload();
            dirty = false;
        }
    }

    public synchronized boolean mergePixels(int[] argbPixels, boolean[] coverageMask) {
        if (image == null || argbPixels == null || argbPixels.length != RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS) {
            return false;
        }
        // 把内部 native ABGR 读回时转回 ARGB，使合并比较与入参 argbPixels 同处 ARGB 域。
        int[] current = new int[RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS];
        for (int y = 0; y < RoadMapTileSpec.TILE_PIXELS; y++) {
            for (int x = 0; x < RoadMapTileSpec.TILE_PIXELS; x++) {
                current[y * RoadMapTileSpec.TILE_PIXELS + x] = toNativeAbgr(image.getPixelRGBA(x, y));
            }
        }
        boolean applied = RoadPlannerTileMergeRules.merge(current, argbPixels, coverageMask,
                RoadPlannerTileMergeRules.knownMask(argbPixels));
        if (!applied) {
            return false;
        }
        for (int y = 0; y < RoadMapTileSpec.TILE_PIXELS; y++) {
            for (int x = 0; x < RoadMapTileSpec.TILE_PIXELS; x++) {
                image.setPixelRGBA(x, y, toNativeAbgr(current[y * RoadMapTileSpec.TILE_PIXELS + x]));
            }
        }
        loadedFromCache = true;
        dirty = true;
        if (texture != null) {
            texture.upload();
            dirty = false;
        }
        return true;
    }

    public synchronized void render(GuiGraphics graphics, int x, int y, int size) {
        if (textureId == null) {
            return;
        }
        if (dirty && texture != null) {
            texture.upload();
            dirty = false;
        }
        graphics.blit(textureId, x, y, 0, 0, size, size, size, size);
        markAccessed();
    }

    public void markAccessed() {
        lastAccessedAt = System.currentTimeMillis();
    }

    /** ARGB({@code 0xAARRGGBB}) ↔ NativeImage native ABGR({@code 0xAABBGGRR})。对称运算，用于跨格式边界转换。 */
    private static int toNativeAbgr(int argb) {
        return com.monpai.sailboatmod.roadplanner.map.MapBlockColors.argbToNativeAbgr(argb);
    }

    private NativeImage createLoadingImage() {
        NativeImage loading = new NativeImage(NativeImage.Format.RGBA, TILE_PIXEL_SIZE, TILE_PIXEL_SIZE, false);
        int lightColor = 0xFF3A3A3A;
        int darkColor = 0xFF2A2A2A;
        int checkerSize = 8;
        for (int y = 0; y < TILE_PIXEL_SIZE; y++) {
            for (int x = 0; x < TILE_PIXEL_SIZE; x++) {
                boolean light = ((x / checkerSize) + (y / checkerSize)) % 2 == 0;
                loading.setPixelRGBA(x, y, light ? lightColor : darkColor);
            }
        }
        loading.untrack();
        return loading;
    }

    private int[] tilePixels() {
        int[] pixels = new int[TILE_PIXEL_SIZE * TILE_PIXEL_SIZE];
        for (int y = 0; y < TILE_PIXEL_SIZE; y++) {
            for (int x = 0; x < TILE_PIXEL_SIZE; x++) {
                pixels[y * TILE_PIXEL_SIZE + x] = image.getPixelRGBA(x, y);
            }
        }
        return pixels;
    }

    private void writeTilePixels(int[] pixels) {
        if (pixels == null || pixels.length < TILE_PIXEL_SIZE * TILE_PIXEL_SIZE) {
            return;
        }
        for (int y = 0; y < TILE_PIXEL_SIZE; y++) {
            for (int x = 0; x < TILE_PIXEL_SIZE; x++) {
                image.setPixelRGBA(x, y, pixels[y * TILE_PIXEL_SIZE + x]);
            }
        }
    }

    private static int[] chunkPixels(NativeImage chunk) {
        int[] pixels = new int[16 * 16];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                pixels[y * 16 + x] = chunk.getPixelRGBA(x, y);
            }
        }
        return pixels;
    }

    private boolean looksLikePlaceholder(NativeImage candidate) {
        if (candidate == null || candidate.getWidth() != TILE_PIXEL_SIZE || candidate.getHeight() != TILE_PIXEL_SIZE) {
            return true;
        }
        int first = candidate.getPixelRGBA(0, 0);
        int middle = candidate.getPixelRGBA(TILE_PIXEL_SIZE / 2, TILE_PIXEL_SIZE / 2);
        int corner = candidate.getPixelRGBA(TILE_PIXEL_SIZE - 1, TILE_PIXEL_SIZE - 1);
        if (first == middle && middle == corner) {
            return false;
        }
        int sampleA = candidate.getPixelRGBA(8, 8);
        int sampleB = candidate.getPixelRGBA(24, 8);
        int sampleC = candidate.getPixelRGBA(8, 24);
        return sampleA == sampleC && sampleA != sampleB;
    }

    private void uploadTexture() {
        if (image == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getTextureManager() == null) {
            return;
        }
        texture = new DynamicTexture(image);
        textureId = minecraft.getTextureManager().register(textureRegistrationName(key), texture);
    }

    static String textureRegistrationName(RoadPlannerTileKey key) {
        return sanitizeTexturePath("road_planner_tile_" + key.worldId()
                + "_" + key.dimensionId()
                + "_lod_" + key.lod().blocksPerPixel()
                + "_" + key.tileX()
                + "_" + key.tileZ());
    }

    private static String sanitizeTexturePath(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_");
    }

    private void closeTextureOnly() {
        Minecraft minecraft = Minecraft.getInstance();
        if (textureId != null && minecraft != null && minecraft.getTextureManager() != null) {
            minecraft.getTextureManager().release(textureId);
            textureId = null;
        }
        if (texture != null) {
            texture.close();
            texture = null;
        }
        if (image != null) {
            image.close();
            image = null;
        }
    }

    @Override
    public void close() {
        closeTextureOnly();
    }
}
