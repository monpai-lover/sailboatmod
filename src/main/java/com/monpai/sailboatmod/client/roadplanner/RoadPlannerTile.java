package com.monpai.sailboatmod.client.roadplanner;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

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

    public void updateChunk(RoadPlannerChunkImage chunkImage, int chunkXInTile, int chunkZInTile) {
        if (image == null || chunkImage == null || !chunkImage.isMeaningful()) {
            return;
        }
        NativeImage chunk = chunkImage.image();
        int startX = chunkXInTile * 16;
        int startZ = chunkZInTile * 16;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                image.setPixelRGBA(startX + x, startZ + z, chunk.getPixelRGBA(x, z));
            }
        }
        dirty = true;
    }

    public void render(GuiGraphics graphics, int x, int y, int size) {
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
        texture = new DynamicTexture(image);
        textureId = Minecraft.getInstance().getTextureManager().register(
                "road_planner_tile_" + key.worldId() + "_" + key.dimensionId() + "_" + key.tileX() + "_" + key.tileZ(),
                texture
        );
    }

    private void closeTextureOnly() {
        if (textureId != null) {
            Minecraft.getInstance().getTextureManager().release(textureId);
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
